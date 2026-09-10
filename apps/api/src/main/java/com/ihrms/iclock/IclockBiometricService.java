package com.ihrms.iclock;

import com.ihrms.domain.model.IclockBiometricTemplate;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockDeviceCommand;
import com.ihrms.domain.model.IclockDeviceEnrolment;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.repository.IclockBiometricTemplateRepository;
import com.ihrms.domain.repository.IclockDeviceEnrolmentRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * One enrolment, every terminal in the building.
 *
 * <p>A person enrols once, at whichever machine they are standing at, and can then badge at any door
 * in that building. Without this they would have to walk to each terminal in turn and give the same
 * finger four times, which is what the operators have been doing.
 *
 * <p><b>The capture half needs no protocol.</b> The design assumed templates would have to be
 * fetched — {@code DATA QUERY FINGERTMP}, replies as {@code table=BIODATA}. Neither is true of this
 * fleet: it has never sent a BIODATA push, and it volunteers the template inside OPERLOG the moment
 * an enrolment finishes. So this service listens rather than asks, and the outbound half is the only
 * part that needs commands.
 *
 * <p><b>NOTHING HERE QUEUES BY ITSELF.</b> An earlier version of this service propagated
 * automatically on capture, on the reasoning that a person physically enrolling is a different kind
 * of trigger from a console edit. That reasoning did not survive contact: the fleet name sync obeyed
 * its building scope exactly and still put one building's people onto the other's screens, because
 * the roster underneath it was wrong. An automatic write is only ever as correct as the data it
 * trusts, and there is nobody looking at the moment it fires.
 *
 * <p>So capture STORES the template and records which terminal holds it, and stops. Spreading it is
 * {@link #resync}, from that person's row, pressed by somebody who can see whose row it is — and the
 * building boundary in the command service makes the cross-building write impossible rather than
 * merely unlikely.
 */
@Service
public class IclockBiometricService {

  private static final Logger log = LoggerFactory.getLogger(IclockBiometricService.class);

  private final IclockBiometricTemplateRepository templates;
  private final IclockDeviceEnrolmentRepository enrolments;
  private final IclockDeviceRepository devices;
  private final IclockPersonRepository people;
  private final IclockCommandService commands;
  private final IclockRawPunchRepository rawPunches;

  public IclockBiometricService(
      IclockBiometricTemplateRepository templates,
      IclockDeviceEnrolmentRepository enrolments,
      IclockDeviceRepository devices,
      IclockPersonRepository people,
      IclockCommandService commands,
      IclockRawPunchRepository rawPunches) {
    this.templates = templates;
    this.enrolments = enrolments;
    this.devices = devices;
    this.people = people;
    this.commands = commands;
    this.rawPunches = rawPunches;
  }

  /** What one OPERLOG push produced, so the ingest log can say so in one line. */
  public record CaptureResult(int templates, int stored, int unchanged, int propagated) {}

  // ------------------------------------------------------------------ capture

  /**
   * Takes the fingerprint templates out of an OPERLOG push and spreads them across the building.
   *
   * <p>Runs in its own transaction, like every other ingest side effect, and for the same reason: a
   * terminal that gets an error instead of an acknowledgement retries the batch forever. Losing a
   * propagation is recoverable — there is a re-sync action for exactly that — while losing the push
   * would lose the enrolment.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CaptureResult capture(String serialNumber, String body) {
    List<IclockOperlog.Template> found = IclockOperlog.templates(body);
    if (found.isEmpty()) {
      return new CaptureResult(0, 0, 0, 0);
    }
    IclockDevice source = devices.findBySerialNumber(serialNumber).orElse(null);
    if (source == null || !"CLAIMED".equals(source.getStatus()) || source.getSiteId() == null) {
      // An unclaimed terminal belongs to no building, so there is nowhere to propagate TO. The push
      // is captured raw either way; claiming the device and re-enrolling is the recovery.
      log.info("iclock: {} sent {} template(s) but is not claimed to a building; not stored",
          serialNumber, found.size());
      return new CaptureResult(found.size(), 0, 0, 0);
    }

    // LEARN THE TERMINAL'S OWN FACE VERSION from what it just sent us. This is the only way it is
    // ever discovered: nothing in the protocol announces it, and without it the funnel cannot tell a
    // compatible push from an incompatible one.
    found.stream()
        .filter(t -> t.bioType() == IclockCommandDialect.TYPE_FACE && t.algoMajor() != null)
        .findFirst()
        .ifPresent(t -> {
          if (!java.util.Objects.equals(source.getFaceAlgoMajor(), t.algoMajor())
              || !java.util.Objects.equals(source.getFaceAlgoMinor(), t.algoMinor())) {
            source.setFaceAlgoMajor(t.algoMajor());
            source.setFaceAlgoMinor(t.algoMinor());
            devices.save(source);
            log.info("iclock: {} reports face algorithm {}.{}",
                serialNumber, t.algoMajor(), t.algoMinor());
          }
        });

    int stored = 0, unchanged = 0;
    for (IclockOperlog.Template t : found) {
      String digest = digestOf(t.template());
      Optional<IclockBiometricTemplate> existing = templates.findBySiteIdAndPinAndBioTypeAndFid(
          source.getSiteId(), t.pin(), t.bioType(), t.fid());

      if (existing.isPresent() && digest.equals(existing.get().getFingerprint())) {
        // IDEMPOTENCE, AND IT MATTERS. A terminal that re-sends its OPERLOG history — which is
        // exactly what one does after a factory reset or a cursor rewind — would otherwise re-push
        // every fingerprint in the building on a single poll.
        unchanged++;
        markSource(source, t, digest);
        continue;
      }

      IclockBiometricTemplate row = existing.orElseGet(IclockBiometricTemplate::new);
      row.setSiteId(source.getSiteId());
      row.setPin(t.pin());
      row.setBioType(t.bioType());
      row.setFid(t.fid());
      row.setTemplate(t.template());
      row.setSize(t.size());
      row.setValid(t.valid());
      row.setFingerprint(digest);
      row.setAlgoMajor(t.algoMajor());
      row.setAlgoMinor(t.algoMinor());
      row.setSourceDeviceId(source.getId());
      row.setCapturedAt(Instant.now());
      row.setPersonId(people.findBySiteIdAndPin(source.getSiteId(), t.pin())
          .map(IclockPerson::getId)
          .orElse(null));
      templates.save(row);
      stored++;
      markSource(source, t, digest);
      // AND STOPS. The other terminals in this building do not get it until an operator asks, from
      // this person's row, having seen whose row it is.
    }

    log.info("iclock: {} captured {} template(s): {} new, {} unchanged; awaiting operator sync",
        serialNumber, found.size(), stored, unchanged);
    return new CaptureResult(found.size(), stored, unchanged, 0);
  }

  /** The capturing terminal already holds the finger; it is enrolled without anything being sent. */
  private void markSource(IclockDevice source, IclockOperlog.Template t, String digest) {
    IclockDeviceEnrolment row = enrolments
        .findByDeviceIdAndPinAndBioTypeAndFid(source.getId(), t.pin(), t.bioType(), t.fid())
        .orElseGet(IclockDeviceEnrolment::new);
    row.setDeviceId(source.getId());
    row.setPin(t.pin());
    row.setBioType(t.bioType());
    row.setFid(t.fid());
    row.setFingerprint(digest);
    row.setStatus(IclockDeviceEnrolment.SOURCE);
    row.setCommandId(null);
    row.setFailureReason(null);
    enrolments.save(row);
  }

  // ------------------------------------------------------------------ propagation

  /**
   * Records what a terminal said about a template push.
   *
   * <p>Called from the ack path. An acknowledgement here means something stronger than it does for a
   * name update: the device has taken the template into its own store, so PRESENT is a claim about
   * hardware state rather than about delivery.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordTemplateAck(String commandId, String returnValue) {
    // The verdict is DERIVED here rather than passed in. Taking a boolean would put the reading of
    // Return= in two places, free to drift apart, and the structural rule against a flag parameter
    // on a transactional method exists to catch exactly that.
    boolean ok = IclockCommandDialect.isSuccess(returnValue);
    enrolments.findByCommandId(commandId).ifPresent(state -> {
      state.setStatus(ok ? IclockDeviceEnrolment.PRESENT : IclockDeviceEnrolment.FAILED);
      state.setFailureReason(ok ? null : "Terminal returned " + returnValue);
      enrolments.save(state);
    });
  }

  // ------------------------------------------------------------------ console

  /** One terminal's line in the enrolment picture. */
  public record DeviceEnrolmentRow(
      String deviceId, String deviceName, String serialNumber, String direction,
      int fingers, int faces, String status, String failureReason) {}

  /** One credential and how often this person actually uses it. */
  public record PassesBy(String mode, String label, long punches, int percent) {}

  /** Where this person's fingers are, terminal by terminal. */
  public record EnrolmentState(
      String pin, int fingersHeld, int facesHeld, int devices, int enrolledOn,
      List<DeviceEnrolmentRow> rows,
      /**
       * HOW THEY ACTUALLY GET THROUGH THE DOOR, which is not the same question as what is enrolled.
       * A person can hold two fingerprints on every terminal and pass by face every single day; the
       * enrolment counts above would look healthy while the thing keeping them in the building is a
       * template this system has never seen.
       */
      List<PassesBy> passesBy) {}

  /**
   * "Enrolled on 4 of 4" — and, when it is not 4 of 4, which door is the problem.
   *
   * <p>{@code readOnly = true} puts Hibernate in FlushMode.MANUAL, so this read cannot write.
   */
  @Transactional(readOnly = true)
  public EnrolmentState stateFor(String personId) {
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    List<IclockBiometricTemplate> held = templates.findBySiteIdAndPinOrderByBioTypeAscFidAsc(
        person.getSiteId(), person.getPin());
    List<IclockDeviceEnrolment> states = enrolments.findByPin(person.getPin());
    List<IclockDevice> here = devices.findBySiteId(person.getSiteId()).stream()
        .filter(d -> "CLAIMED".equals(d.getStatus()))
        .toList();

    List<DeviceEnrolmentRow> rows = new ArrayList<>();
    int enrolledOn = 0;
    for (IclockDevice d : here) {
      List<IclockDeviceEnrolment> mine =
          states.stream().filter(s -> s.getDeviceId().equals(d.getId())).toList();
      String status = worstOf(mine);
      if (IclockDeviceEnrolment.SOURCE.equals(status)
          || IclockDeviceEnrolment.PRESENT.equals(status)) {
        enrolledOn++;
      }
      rows.add(new DeviceEnrolmentRow(
          d.getId(),
          d.getName() == null ? d.getSerialNumber() : d.getName(),
          d.getSerialNumber(),
          d.getDirection(),
          (int) mine.stream().filter(m -> m.getBioType() != IclockCommandDialect.TYPE_FACE).count(),
          (int) mine.stream().filter(m -> m.getBioType() == IclockCommandDialect.TYPE_FACE).count(),
          status,
          mine.stream().map(IclockDeviceEnrolment::getFailureReason)
              .filter(r -> r != null && !r.isBlank()).findFirst().orElse(null)));
    }
    return new EnrolmentState(
        person.getPin(),
        (int) held.stream().filter(t -> t.getBioType() != IclockCommandDialect.TYPE_FACE).count(),
        (int) held.stream().filter(t -> t.getBioType() == IclockCommandDialect.TYPE_FACE).count(),
        here.size(), enrolledOn, rows,
        passesBy(person.getPin(), person.getSiteId()));
  }

  /**
   * The state a terminal is in for one person, reduced to a single word.
   *
   * <p>A device holding two of somebody's three fingers is not "enrolled", and saying so would send
   * an operator away from a problem. The worst state wins.
   */
  private static String worstOf(List<IclockDeviceEnrolment> rows) {
    if (rows.isEmpty()) {
      return "NONE";
    }
    if (rows.stream().anyMatch(r -> IclockDeviceEnrolment.FAILED.equals(r.getStatus()))) {
      return IclockDeviceEnrolment.FAILED;
    }
    if (rows.stream().anyMatch(r -> IclockDeviceEnrolment.PENDING.equals(r.getStatus()))) {
      return IclockDeviceEnrolment.PENDING;
    }
    if (rows.stream().anyMatch(r -> IclockDeviceEnrolment.SOURCE.equals(r.getStatus()))) {
      return IclockDeviceEnrolment.SOURCE;
    }
    return IclockDeviceEnrolment.PRESENT;
  }

  /**
   * How this person has actually been passing, biggest share first.
   *
   * <p>Counted from the RAW punches rather than the promoted ones: a burst-collapsed row was still a
   * real presentation of a face or a finger, and the question is which credential somebody uses, not
   * which row survived de-duplication.
   *
   * <p>Percentages are rounded and may not total 100. That is preferable to inventing a largest
   * remainder — the number is here to say "this person is a face user", not to be summed.
   */
  private List<PassesBy> passesBy(String pin, String siteId) {
    List<Object[]> rows = rawPunches.countByVerifyModeForPin(pin, siteId);
    long total = rows.stream().mapToLong(r -> ((Number) r[1]).longValue()).sum();
    if (total == 0) {
      return List.of();
    }
    java.util.Map<IclockVerifyMode, Long> byMode = new java.util.EnumMap<>(IclockVerifyMode.class);
    for (Object[] r : rows) {
      byMode.merge(
          IclockVerifyMode.of((String) r[0]), ((Number) r[1]).longValue(), Long::sum);
    }
    return byMode.entrySet().stream()
        .sorted(java.util.Map.Entry.<IclockVerifyMode, Long>comparingByValue().reversed())
        .map(e -> new PassesBy(
            e.getKey().name(), e.getKey().label(), e.getValue(),
            (int) Math.round(100.0 * e.getValue() / total)))
        .toList();
  }

  /**
   * Re-pushes every finger this person has to every terminal in their building.
   *
   * <p><b>The only way a template ever leaves this server.</b> Capture stores it and stops; this is
   * what spreads it, and somebody has to press it from the person's own row.
   *
   * <p>Deliberately unconditional: it does not skip terminals that claim to be PRESENT already,
   * because the reason somebody is pressing it is usually that the claim looks wrong.
   *
   * <p>Building-scoped by its own query AND by the boundary check every queued command passes
   * through. Belt and braces on purpose — the query being right is a property of this method, while
   * the boundary is a property of the system.
   *
   * @return how many commands were queued
   */
  @Transactional
  public int resync(String personId, String actorId) {
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    List<IclockBiometricTemplate> held = templates.findBySiteIdAndPinOrderByBioTypeAscFidAsc(
        person.getSiteId(), person.getPin());
    if (held.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "No fingerprint has ever reached the server for this person. They need to enrol at a "
              + "terminal first — the template arrives here by itself when they do.");
    }
    List<IclockDevice> targets = devices.findBySiteId(person.getSiteId()).stream()
        .filter(d -> "CLAIMED".equals(d.getStatus()))
        .toList();

    int queued = 0;
    for (IclockBiometricTemplate t : held) {
      for (IclockDevice d : targets) {
        if (d.getId().equals(t.getSourceDeviceId())) {
          // The terminal it was captured on already holds it; re-pushing a device its own template
          // is a command that can only fail or do nothing.
          continue;
        }
        IclockDeviceCommand c = commands.queueTemplatePush(d, t, actorId);
        IclockDeviceEnrolment state = enrolments
            .findByDeviceIdAndPinAndBioTypeAndFid(d.getId(), t.getPin(), t.getBioType(), t.getFid())
            .orElseGet(IclockDeviceEnrolment::new);
        state.setDeviceId(d.getId());
        state.setPin(t.getPin());
        state.setBioType(t.getBioType());
        state.setFid(t.getFid());
        state.setFingerprint(t.getFingerprint());
        state.setStatus(IclockDeviceEnrolment.PENDING);
        state.setCommandId(c.getId());
        state.setFailureReason(null);
        enrolments.save(state);
        queued++;
      }
    }
    log.info("iclock: re-sync queued {} template push(es) for pin {}", queued, person.getPin());
    return queued;
  }

  /** SHA-256 of the base64, hex. Identity only — never used as a security boundary. */
  private static String digestOf(String template) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(template.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required by the JDK", e);
    }
  }
}
