package com.ihrms.iclock;

import com.ihrms.domain.model.IclockBiometricTemplate;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockDeviceCommand;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.repository.IclockDeviceCommandRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
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
 * The device command channel — the first path in this system that WRITES to hardware.
 *
 * <p>Everything before it read from the terminals. A command changes a device's screen and its user
 * table, and the failure modes are physical: a wrong name on 250 screens, or an enrolment deleted so
 * that somebody cannot get through the gate until they re-register a fingerprint. The safety here is
 * therefore structural rather than advisory.
 *
 * <ul>
 *   <li><b>Nothing auto-queues.</b> Editing a person does not enqueue anything. An operator asks, by
 *       name, per command. That is why every row carries {@code createdBy} NOT NULL.
 *   <li><b>A kill switch that fails closed.</b> {@code app.iclock.commands-enabled} defaults FALSE.
 *       With it off, {@link #nextFor} serves nothing at all, whatever is queued.
 *   <li><b>A per-device cap.</b> A queue that can grow without bound is a queue that will one day be
 *       filled by a loop nobody meant to write.
 *   <li><b>Bounded retries.</b> A command served {@code maxServes} times without an ack becomes
 *       FAILED and is surfaced. Serving it forever would mean ~2 attempts a second, per device,
 *       indefinitely.
 * </ul>
 */
@Service
public class IclockCommandService {

  private static final Logger log = LoggerFactory.getLogger(IclockCommandService.class);

  static final String PENDING = "PENDING";
  static final String SENT = "SENT";
  static final String ACKED = "ACKED";
  static final String FAILED = "FAILED";

  private final IclockDeviceCommandRepository commands;
  private final IclockDeviceRepository devices;
  private final IclockPersonRepository people;
  private final IclockSiteRepository sites;
  private final IclockProperties props;

  public IclockCommandService(
      IclockDeviceCommandRepository commands,
      IclockDeviceRepository devices,
      IclockPersonRepository people,
      IclockSiteRepository sites,
      IclockProperties props) {
    this.commands = commands;
    this.devices = devices;
    this.people = people;
    this.sites = sites;
    this.props = props;
  }

  /** Whether the channel may serve anything at all. Off by default; see IclockProperties. */
  public boolean enabled() {
    return props.commandsEnabled();
  }

  // ------------------------------------------------------------------ queueing

  /**
   * Queues a name update for one person on every CLAIMED device at their building.
   *
   * <p>Every device, because a pin is enrolled on the terminals of the building the person belongs
   * to and their name shows on whichever one they walk up to. Updating one and not the others would
   * leave the fleet disagreeing about who somebody is.
   *
   * @return the commands created, one per device
   */
  @Transactional
  public List<IclockDeviceCommand> queueNameUpdate(String personId, String actorId) {
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    if (person.getName() == null || person.getName().isBlank()) {
      // Pushing an empty name would blank the device screen — strictly worse than the slug it is
      // meant to replace, and not obviously reversible from the terminal.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "This person has no name yet. Give them one before pushing it.");
    }
    List<IclockDeviceCommand> created = new ArrayList<>();
    for (IclockDevice device : claimedDevicesAt(person.getSiteId())) {
      created.add(queue(
          device,
          "UPDATE_USERINFO",
          IclockCommandDialect.updateUserInfo(person.getPin(), person.getName()),
          person.getId(),
          person.getPin(),
          actorId));
    }
    if (created.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "No claimed terminal at this building to push to.");
    }
    return created;
  }

  /** Queues a clock set for one device, in its own site's timezone. */
  @Transactional
  public IclockDeviceCommand queueTimeSync(String deviceId, String actorId) {
    IclockDevice device = claimedDevice(deviceId);
    // DEFERRED, not stamped now. The clock is filled in when the device actually asks for the
    // command, so a SET_TIME sitting behind a few hundred name pushes still sets the right time.
    return queue(
        device, "SET_TIME", IclockCommandDialect.setTimeDeferred(), null, null, actorId);
  }

  /**
   * Queues removal of an enrolment.
   *
   * <p><b>Guarded.</b> Only a pin with no roster row, or one whose person is deactivated, may be
   * deleted. Deleting an active person's enrolment takes their fingerprint off the terminal and
   * leaves them unable to get in until they physically re-register — a consequence no console button
   * should be able to cause by a mis-click.
   */
  @Transactional
  public IclockDeviceCommand queueUserDelete(String deviceId, String rawPin, String actorId) {
    IclockDevice device = claimedDevice(deviceId);
    String pin = IclockPin.canonicalOrNull(rawPin);
    if (pin == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a usable pin");
    }
    Optional<IclockPerson> person = people.findBySiteIdAndPin(device.getSiteId(), pin);
    if (person.isPresent() && person.get().isActive()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Pin " + pin + " belongs to " + (person.get().getName() == null ? "an active person"
              : person.get().getName()) + ", who is still active. Deactivate them first — deleting "
              + "the enrolment removes their fingerprint from the terminal.");
    }
    return queue(
        device, "DELETE_USER", IclockCommandDialect.deleteUser(pin),
        person.map(IclockPerson::getId).orElse(null), pin, actorId);
  }

  /** How many fingers a terminal will hold for one person; FID is 0-9 on this firmware family. */
  static final int MAX_FINGER_INDEX = 9;

  /** Scans the device asks for before accepting a template — the firmware's own default. */
  private static final int ENROL_RETRIES = 3;

  /** What an enrolment trigger produced: the commands queued, and what the operator must do next. */
  public record EnrolmentTrigger(
      List<IclockDeviceCommand> queued, String deviceName, String personName,
      int bioType, int fingerIndex) {}

  /**
   * Puts one terminal into fingerprint capture mode for one person.
   *
   * <p><b>ONE device, not the building.</b> Every other command here fans out to all of a building's
   * terminals, because a name should read the same wherever somebody walks up. This one cannot: the
   * person has to stand at a specific machine and put a finger on it, so the operator picks which,
   * and telling three terminals to wait for a finger would leave two of them stuck.
   *
   * <p><b>The user row is created first, deliberately.</b> A template attaches to a user the device
   * already holds, so a person enrolling for the first time needs {@code UPDATE USERINFO} to land
   * before {@code ENROLL_FP} — the queue is FIFO per device, so ordering them here is enough. That
   * is not the forbidden auto-queue: the operator asked for an enrolment, and this is what an
   * enrolment consists of.
   *
   * <p><b>An ack does not mean enrolled.</b> {@code Return=0} means the terminal understood the
   * command. Whether a template exists afterwards depends on somebody being there to give one, and
   * this fleet has never been sent this verb — the firmware may open a capture prompt, ignore it
   * silently, or answer an error we have not seen. The proof of an enrolment is the person's next
   * successful punch, and the console says so rather than claiming success from the ack.
   */
  @Transactional
  public EnrolmentTrigger queueEnrolment(
      String personId, String deviceId, int bioType, int fingerIndex, String actorId) {
    boolean face = bioType == IclockCommandDialect.TYPE_FACE;
    if (!face && (fingerIndex < 0 || fingerIndex > MAX_FINGER_INDEX)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Finger must be 0-" + MAX_FINGER_INDEX + ".");
    }
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    if (!person.isActive()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "This person is deactivated. Enrolling a finger would let them back through the gate.");
    }
    if (person.getName() == null || person.getName().isBlank()) {
      // The user row is created by the name push that precedes the trigger, so a nameless person
      // would enrol against a blank screen entry nobody can identify at the terminal.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Give this person a name first — the terminal shows it during capture.");
    }
    IclockDevice device = claimedDevice(deviceId);
    if (!device.getSiteId().equals(person.getSiteId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "That terminal is in a different building. Enrol on a terminal where this person works.");
    }

    List<IclockDeviceCommand> queued = new ArrayList<>();
    queued.add(queue(
        device, "UPDATE_USERINFO",
        IclockCommandDialect.updateUserInfo(person.getPin(), person.getName()),
        person.getId(), person.getPin(), actorId));
    queued.add(queue(
        device,
        face ? "ENROLL_BIO" : "ENROLL_FP",
        face ? IclockCommandDialect.enrolFace(person.getPin(), ENROL_RETRIES, true)
            : IclockCommandDialect.enrolFinger(person.getPin(), fingerIndex, ENROL_RETRIES, true),
        person.getId(), person.getPin(), actorId));
    log.info("iclock: {} enrolment queued for pin {}{} on {}",
        face ? "face" : "fingerprint", person.getPin(),
        face ? "" : " finger " + fingerIndex, device.getSerialNumber());
    return new EnrolmentTrigger(
        queued, device.getName() == null ? device.getSerialNumber() : device.getName(),
        person.getName(), bioType, face ? 0 : fingerIndex);
  }

  /**
   * Asks a terminal for its own user table.
   *
   * <p>The counterpart to the USERINFO ingest, which was built for a push no device here has ever
   * volunteered. The device answers by POSTing {@code table=USERINFO} to {@code cdata}, so the reply
   * arrives through the ordinary ingest path and seeds roster rows for pins nobody knows yet.
   *
   * <p>Read-only on the device's side — it changes nothing there, which makes it the one new verb
   * that is safe to try on a live gate during a shift.
   */
  @Transactional
  public IclockDeviceCommand queueUserQuery(String deviceId, String rawPin, String actorId) {
    IclockDevice device = claimedDevice(deviceId);
    String pin = rawPin == null || rawPin.isBlank() ? null : IclockPin.canonicalOrNull(rawPin);
    if (rawPin != null && !rawPin.isBlank() && pin == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a usable pin");
    }
    return queue(
        device, "QUERY_USERINFO", IclockCommandDialect.queryUserInfo(pin), null, pin, actorId);
  }

  /**
   * Queues one fingerprint template onto one terminal.
   *
   * <p>Takes the device and template rows rather than ids because the only caller already holds
   * both, having just decided that this terminal is missing this finger. Re-resolving them here
   * would be two queries to reach the same objects and one more chance to disagree about which
   * building is in play.
   *
   * <p>The payload is large by the standards of this channel - a template is 1.3-2KB of base64
   * against about 40 bytes for a name - which is why the per-device cap matters more here than
   * anywhere else. A building-wide re-sync is fingers MULTIPLIED BY terminals.
   */
  @Transactional
  public IclockDeviceCommand queueTemplatePush(
      IclockDevice device, IclockBiometricTemplate template, String actorId) {
    assertSameFaceAlgorithm(device, template);
    return queue(
        device,
        template.getBioType() == IclockCommandDialect.TYPE_FACE
            ? "UPDATE_BIODATA" : "UPDATE_FINGERTMP",
        IclockCommandDialect.updateTemplate(
            template.getBioType(), template.getPin(), template.getFid(), template.getSize(),
            template.getValid(), template.getTemplate(),
            template.getAlgoMajor(), template.getAlgoMinor()),
        template.getPersonId(),
        template.getPin(),
        actorId);
  }

  /**
   * Command kinds that put a PERSON onto a terminal, and are therefore subject to the building
   * boundary.
   *
   * <p>An allowlist rather than a denylist, so a verb added later is guarded until somebody decides
   * otherwise. {@code DELETE_USER} is deliberately absent: removing somebody from a building they do
   * not belong to is the REMEDY for a boundary breach, and a rule that blocked the cleanup along
   * with the damage would be worse than no rule.
   */
  private static final java.util.Set<String> CARRIES_A_PERSON = java.util.Set.of(
      "UPDATE_USERINFO", "ENROLL_FP", "ENROLL_BIO", "UPDATE_FINGERTMP", "UPDATE_BIODATA");

  /**
   * <b>THE BUILDING BOUNDARY.</b> No command may carry a person to a terminal in a building where
   * they hold no ACTIVE roster row.
   *
   * <p>Enforced here, in the single funnel every queued command passes through, rather than in each
   * caller. Callers were already building-scoped and the fleet name sync obeyed them exactly — and a
   * building's terminals still ended up showing people from the other building, because the ROSTER
   * said they worked there. Scoping a query correctly is not the same as being unable to cross the
   * line: the first is a property of one code path, the second is a property of the system.
   *
   * <p>So this check does not ask what the caller intended. It asks the only question that matters
   * at the moment of writing to hardware — does this person work in this building, right now — and
   * refuses if the answer is no.
   */
  private void assertWithinBuilding(IclockDevice device, String kind, String pin) {
    if (pin == null || device.getSiteId() == null || !CARRIES_A_PERSON.contains(kind)) {
      return;
    }
    boolean belongsHere = people.findBySiteIdAndPin(device.getSiteId(), pin)
        .map(IclockPerson::isActive)
        .orElse(false);
    if (!belongsHere) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Pin " + pin + " has no active roster row in this terminal's building. Buildings do not "
              + "share registers — add them to this building's roster first, or send this to a "
              + "terminal where they actually work.");
    }
  }

  /**
   * <b>A FACE TEMPLATE MAY ONLY GO TO A TERMINAL RUNNING THE SAME ALGORITHM.</b>
   *
   * <p>Measured, not feared: the NES cafeteria readers report face version 36.1 and the ZHM gates
   * report 39.3. Those are not two revisions of one format that might interoperate, they are the
   * reason a cross-family push would land as a stored template that never matches a live face — a
   * failure with no error, discovered by somebody standing at a door.
   *
   * <p>REFUSED HERE rather than attempted and read afterwards. An attempt costs a person their
   * enrolment on that terminal and leaves a plausible-looking row behind; the refusal costs an error
   * message. An UNKNOWN version on either side is also a refusal — this is a proof requirement, and
   * "we have never asked that terminal" is not proof.
   *
   * <p>Fingerprints are exempt. They carry no version on this firmware and have been observed moving
   * between both families, so they propagate fleet-wide as before.
   */
  private void assertSameFaceAlgorithm(IclockDevice target, IclockBiometricTemplate template) {
    if (template.getBioType() != IclockCommandDialect.TYPE_FACE) {
      return;
    }
    Integer tMaj = template.getAlgoMajor(), tMin = template.getAlgoMinor();
    Integer dMaj = target.getFaceAlgoMajor(), dMin = target.getFaceAlgoMinor();
    if (tMaj == null || dMaj == null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Face templates only move between terminals proven to run the same algorithm, and "
              + (tMaj == null ? "this template has no recorded version"
                  : "that terminal has never been audited")
              + ". Audit the terminal first, or enrol the face on it directly.");
    }
    if (!tMaj.equals(dMaj) || !java.util.Objects.equals(tMin, dMin)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "That face was captured on algorithm " + tMaj + "." + tMin + " and this terminal runs "
              + dMaj + "." + dMin + ". A template does not convert between them - the person has to "
              + "enrol their face on this terminal.");
    }
  }

  private IclockDeviceCommand queue(
      IclockDevice device, String kind, String payload,
      String personId, String pin, String actorId) {
    assertWithinBuilding(device, kind, pin);
    long waiting = commands.countByDeviceIdAndStatus(device.getId(), PENDING);
    if (waiting >= props.maxPendingCommandsPerDevice()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "That terminal already has " + waiting + " commands waiting. Let it catch up first.");
    }
    IclockDeviceCommand c = new IclockDeviceCommand();
    c.setDeviceId(device.getId());
    c.setKind(kind);
    c.setPayload(payload);
    c.setStatus(PENDING);
    c.setPersonId(personId);
    c.setDevicePin(pin);
    c.setCreatedBy(actorId == null ? "unknown" : actorId);
    return commands.save(c);
  }

  /**
   * <b>BULK NAME SYNC IS GONE, DELIBERATELY.</b>
   *
   * <p>It existed as a building-wide pass and it ran once, correctly, across the fleet. What it
   * showed is that a bulk write is only ever as right as the roster underneath it: the pass obeyed
   * its building scope exactly and still put people from one building onto the other's screens,
   * because the roster said they worked there. A per-person action has the same failure available to
   * it and a hundredth of the reach, and somebody is looking at the row when they press it.
   *
   * <p>The replacement is {@link #queueNameUpdate} from a person's own row, plus the building
   * boundary above, which now makes the bad write impossible rather than merely unlikely.
   */
  // ------------------------------------------------------------------ serving

  /**
   * The next line to hand this device, or empty when there is nothing to say.
   *
   * <p>Runs inside the device's own poll, which is why it is REQUIRES_NEW: a failure here must not
   * take down the poll response, because a terminal that gets an error instead of a reply retries in
   * a tight loop.
   *
   * <p>Serving marks SENT immediately rather than on ack. The alternative — leaving it PENDING until
   * acknowledged — re-serves the same command on the next poll a second later, which for a device
   * that acts on it but acks slowly means executing it repeatedly.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<String> nextFor(String serialNumber) {
    if (!enabled() || serialNumber == null) {
      return Optional.empty();
    }
    IclockDevice device = devices.findBySerialNumber(serialNumber).orElse(null);
    if (device == null || !"CLAIMED".equals(device.getStatus())) {
      return Optional.empty();
    }
    IclockDeviceCommand next = commands
        .findFirstByDeviceIdAndStatusOrderByCreatedAtAsc(device.getId(), PENDING)
        .orElse(null);
    if (next == null) {
      return Optional.empty();
    }
    next.setServeCount(next.getServeCount() + 1);
    next.setSentAt(Instant.now());
    if (next.getServeCount() > props.maxCommandServes()) {
      next.setStatus(FAILED);
      next.setCompletedAt(Instant.now());
      next.setFailureReason(
          "Served " + (next.getServeCount() - 1) + " times with no acknowledgement. Not retried.");
      commands.save(next);
      log.warn("iclock: command {} to {} gave up after {} serves",
          next.getId(), serialNumber, next.getServeCount() - 1);
      return Optional.empty();
    }
    next.setStatus(SENT);
    // Resolve any deferred clock HERE, at the moment of serving, and keep what was actually sent on
    // the row so the log says what the device was told rather than what was queued.
    ZoneId zone = sites.findById(device.getSiteId())
        .map(s -> zoneOf(s.getTimezone()))
        .orElse(com.ihrms.attendance.ShiftConfig.ZONE);
    String wire = IclockCommandDialect.resolve(next.getPayload(), Instant.now(), zone);
    if (!wire.equals(next.getPayload())) {
      next.setPayload(wire);
    }
    commands.save(next);
    log.info("iclock: serving command {} ({}) to {}", next.getId(), next.getKind(), serialNumber);
    return Optional.of(IclockCommandDialect.serve(next.getId(), wire));
  }

  /**
   * Records a device's reply to a command.
   *
   * <p>Tolerant by design, and it stays that way now that the shape is known. Both platforms reply
   * {@code ID=<id>&Return=0&CMD=DATA} in a POST body, as documented — but an unrecognised id or a
   * reply nobody expected is still logged and shrugged off rather than failed, because the cost of
   * being wrong here is a device retrying in a tight loop. The raw capture filter has the request
   * verbatim either way.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<AckOutcome> recordAck(String commandId, String returnValue, String rawBody) {
    if (commandId == null || commandId.isBlank()) {
      log.warn("iclock: devicecmd ack with no command id; body captured raw: {}", rawBody);
      return Optional.empty();
    }
    IclockDeviceCommand c = commands.findById(commandId).orElse(null);
    if (c == null) {
      log.warn("iclock: devicecmd ack for unknown command {}; body: {}", commandId, rawBody);
      return Optional.empty();
    }
    boolean ok = IclockCommandDialect.isSuccess(returnValue);
    c.setAckRaw(rawBody);
    c.setAckReturn(returnValue);
    c.setStatus(ok ? ACKED : FAILED);
    c.setCompletedAt(Instant.now());
    if (!ok) {
      c.setFailureReason("Device returned " + returnValue);
    }
    commands.save(c);
    log.info("iclock: command {} {} (Return={})", commandId, ok ? "ACKED" : "FAILED", returnValue);
    return Optional.of(new AckOutcome(commandId, c.getKind(), ok, returnValue));
  }

  /**
   * What an acknowledgement turned out to be.
   *
   * <p>Returned rather than acted on here so that a template ack can update the enrolment picture
   * without this service knowing about the one that owns it - which would be a cycle, since
   * propagation queues its commands through this service.
   */
  public record AckOutcome(String commandId, String kind, boolean ok, String returnValue) {}

  // ------------------------------------------------------------------ reading

  @Transactional(readOnly = true)
  public List<IclockDeviceCommand> logFor(String deviceId, int limit) {
    return commands.findByDeviceIdOrderByCreatedAtDesc(
        deviceId, org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 200)));
  }

  @Transactional(readOnly = true)
  public List<IclockDeviceCommand> outstanding() {
    return commands.findByStatusInOrderByCreatedAtAsc(List.of(PENDING, SENT));
  }

  private List<IclockDevice> claimedDevicesAt(String siteId) {
    return devices.findBySiteId(siteId).stream()
        .filter(d -> "CLAIMED".equals(d.getStatus()))
        .toList();
  }

  private IclockDevice claimedDevice(String deviceId) {
    IclockDevice d = devices.findById(deviceId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terminal not found"));
    if (!"CLAIMED".equals(d.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Claim this terminal before sending it commands.");
    }
    return d;
  }

  private static ZoneId zoneOf(String tz) {
    try {
      return ZoneId.of(tz);
    } catch (Exception e) {
      return com.ihrms.attendance.ShiftConfig.ZONE;
    }
  }
}
