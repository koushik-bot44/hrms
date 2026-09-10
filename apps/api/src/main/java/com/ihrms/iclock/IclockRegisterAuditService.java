package com.ihrms.iclock;

import com.ihrms.domain.model.IclockBiometricTemplate;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockDeviceCommand;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockRegisterAudit;
import com.ihrms.domain.model.IclockRegisterEntry;
import com.ihrms.domain.repository.IclockBiometricTemplateRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockDeviceUserNameRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockRegisterAuditRepository;
import com.ihrms.domain.repository.IclockRegisterEntryRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * What a terminal is actually holding, compared against the roster.
 *
 * <p><b>The audit exists because the register and the roster drift apart silently.</b> A person
 * leaves, their roster row is deactivated, and their face stays on the door for months. Nothing
 * surfaces it: they simply stop appearing in reports while remaining able to walk in.
 *
 * <p><b>What the terminal tells us.</b> {@code DATA QUERY USERINFO} returns the biometric inventory
 * — BIODATA, BIOPHOTO and FP records — AND a {@code USER} record per pin carrying the terminal's own
 * name for that person.
 *
 * <p>An earlier version of this file claimed the names were not there and dropped NAME DRIFT on that
 * basis. They were always there: a single query to one terminal returned 141 of them. The claim came
 * from an evidence query that used {@code LIMIT 5} while BIOPHOTO rows matched its {@code Name=}
 * pattern through {@code FileName=}, so the USER rows never reached the screen. An evidence query
 * gets no LIMIT, or one LIMIT per record type — a sample is not a census.
 *
 * <ul>
 *   <li><b>CLEAN</b> — an active roster row at this building, named the same on both sides.
 *   <li><b>NAME DRIFT</b> — active here, but the terminal calls them something else. Offers a name
 *       push and never a deletion: a disagreement about a label is not evidence somebody has left.
 *   <li><b>STALE</b> — a roster row here, deactivated. The register is holding somebody who left.
 *   <li><b>UNKNOWN</b> — never rostered here. Register debris, or somebody nobody has added.
 *   <li><b>TEMPLATE GAP</b> — the mirror image: an active person with NO biometric on this terminal,
 *       who therefore cannot open this door at all and will not know until they try.
 * </ul>
 */
@Service
public class IclockRegisterAuditService {

  private static final Logger log = LoggerFactory.getLogger(IclockRegisterAuditService.class);

  /**
   * A terminal answers a query across many pushes over several minutes. An audit older than this
   * with nothing new arriving is treated as finished rather than waiting forever.
   */
  private static final java.time.Duration DUMP_SETTLES_AFTER = java.time.Duration.ofMinutes(10);

  private final IclockRegisterAuditRepository audits;
  private final IclockRegisterEntryRepository entries;
  private final IclockBiometricTemplateRepository templates;
  private final IclockDeviceRepository devices;
  private final IclockDeviceUserNameRepository deviceNames;
  private final IclockPersonRepository people;
  private final IclockCommandService commands;

  public IclockRegisterAuditService(
      IclockRegisterAuditRepository audits,
      IclockRegisterEntryRepository entries,
      IclockBiometricTemplateRepository templates,
      IclockDeviceRepository devices,
      IclockDeviceUserNameRepository deviceNames,
      IclockPersonRepository people,
      IclockCommandService commands) {
    this.audits = audits;
    this.entries = entries;
    this.templates = templates;
    this.devices = devices;
    this.deviceNames = deviceNames;
    this.people = people;
    this.commands = commands;
  }

  // ------------------------------------------------------------------ requesting

  /**
   * Asks one terminal for its register.
   *
   * <p><b>One terminal at a time, deliberately.</b> A single dump was 10 MB across 635 requests in
   * thirty minutes, and the terminals doing the dumping are the same ones people are queueing at. An
   * audit already in flight blocks another rather than letting an operator start six and discover
   * the cost afterwards.
   */
  @Transactional
  public IclockRegisterAudit request(String deviceId, String actorId) {
    IclockDevice device = devices.findById(deviceId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terminal not found"));
    audits.findFirstByDeviceIdAndStatusOrderByRequestedAtDesc(deviceId, IclockRegisterAudit.REQUESTED)
        .filter(a -> a.getRequestedAt().isAfter(Instant.now().minus(DUMP_SETTLES_AFTER)))
        .ifPresent(a -> {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT,
              "This terminal is already sending its register. Let it finish before asking again.");
        });

    IclockDeviceCommand c = commands.queueUserQuery(deviceId, null, actorId);
    IclockRegisterAudit audit = new IclockRegisterAudit();
    audit.setDeviceId(deviceId);
    audit.setCommandId(c.getId());
    audit.setRequestedBy(actorId == null ? "unknown" : actorId);
    audit.setStatus(IclockRegisterAudit.REQUESTED);
    log.info("iclock: register audit requested for {}", device.getSerialNumber());
    return audits.save(audit);
  }

  // ------------------------------------------------------------------ diffing

  /** One pin's line in the review. */
  public record AuditRow(
      String pin, String verdict, String personId, String personName,
      /** What the TERMINAL calls them, when it differs from the roster. */
      String deviceName,
      int fingerCount, String fingerIndexes, boolean hasFace, boolean hasPhoto) {}

  /** An active person who cannot open this door, because the terminal holds nothing for them. */
  public record TemplateGap(String personId, String pin, String name) {}

  /** The whole finding. */
  public record AuditResult(
      String auditId, String deviceId, String deviceName, String status,
      Instant requestedAt, Instant completedAt,
      int pinsOnDevice, int clean, int nameDrift, int stale, int unknown,
      List<AuditRow> rows, List<TemplateGap> gaps) {}

  /**
   * Builds the finding from whatever the terminal has sent.
   *
   * <p>Reads the CAPTURED TEMPLATES rather than re-parsing the request log: the ingest path already
   * stores every template a device pushes, keyed by (site, pin, type, finger), so the inventory is
   * a query rather than a second parse of ten megabytes.
   *
   * <p>{@code readOnly = true} — Hibernate is in FlushMode.MANUAL, so building a finding cannot
   * write one.
   */
  @Transactional(readOnly = true)
  public AuditResult review(String auditId) {
    IclockRegisterAudit audit = audits.findById(auditId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit not found"));
    IclockDevice device = devices.findById(audit.getDeviceId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terminal not found"));
    return decide(audit, device);
  }

  /** The newest audit for a terminal, reviewed. */
  @Transactional(readOnly = true)
  public Optional<AuditResult> latestFor(String deviceId) {
    return audits.findFirstByDeviceIdOrderByRequestedAtDesc(deviceId)
        .map(a -> decide(a, devices.findById(deviceId).orElseThrow()));
  }

  private AuditResult decide(IclockRegisterAudit audit, IclockDevice device) {
    // Everything this building has captured for this terminal, since the audit was asked for.
    List<IclockBiometricTemplate> held = templates.findBySiteId(device.getSiteId());

    Map<String, Inventory> byPin = new LinkedHashMap<>();
    for (IclockBiometricTemplate t : held) {
      if (t.getSourceDeviceId() == null || !t.getSourceDeviceId().equals(device.getId())) {
        // Only what THIS terminal reported. A template captured on its sibling says nothing about
        // what is on this one — that difference is the entire point of auditing per device.
        continue;
      }
      Inventory inv = byPin.computeIfAbsent(t.getPin(), p -> new Inventory());
      if (t.getBioType() == IclockCommandDialect.TYPE_FACE) {
        inv.face = true;
      } else {
        inv.fingers.add(t.getFid());
      }
    }

    Map<String, String> namedByDevice = new LinkedHashMap<>();
    for (var n : deviceNames.findByDeviceId(device.getId())) {
      namedByDevice.put(n.getPin(), n.getDeviceName());
    }

    List<AuditRow> rows = new ArrayList<>();
    int clean = 0, nameDrift = 0, stale = 0, unknown = 0;
    for (Map.Entry<String, Inventory> e : byPin.entrySet()) {
      String pin = e.getKey();
      Inventory inv = e.getValue();
      Optional<IclockPerson> person = people.findBySiteIdAndPin(device.getSiteId(), pin);
      String onDevice = namedByDevice.get(pin);
      String verdict;
      if (person.isEmpty()) {
        verdict = IclockRegisterEntry.UNKNOWN;
        unknown++;
      } else if (!person.get().isActive()) {
        verdict = IclockRegisterEntry.STALE;
        stale++;
      } else if (namesDisagree(person.get().getName(), onDevice)) {
        // A LABEL DISAGREEMENT IS NOT A REASON TO DELETE ANYBODY. This verdict offers a name push
        // and nothing else — the person works here, the terminal is simply calling them by the slug
        // they were enrolled under.
        verdict = NAME_DRIFT;
        nameDrift++;
      } else {
        verdict = IclockRegisterEntry.CLEAN;
        clean++;
      }
      rows.add(new AuditRow(
          pin, verdict,
          person.map(IclockPerson::getId).orElse(null),
          person.map(IclockPerson::getName).orElse(null),
          onDevice,
          inv.fingers.size(),
          inv.fingers.isEmpty() ? null : String.join(", ",
              inv.fingers.stream().map(String::valueOf).toList()),
          inv.face, inv.face));
    }

    // THE MIRROR IMAGE, and the finding operators actually act on: somebody who is on the roster,
    // expected at work, and holds nothing on this terminal. They cannot open this door and will
    // discover it at the door.
    List<TemplateGap> gaps = people.findBySiteIdAndActiveTrue(device.getSiteId()).stream()
        .filter(p -> !byPin.containsKey(p.getPin()))
        .map(p -> new TemplateGap(p.getId(), p.getPin(), p.getName()))
        .toList();

    return new AuditResult(
        audit.getId(), device.getId(),
        device.getName() == null ? device.getSerialNumber() : device.getName(),
        audit.getStatus(), audit.getRequestedAt(), audit.getCompletedAt(),
        byPin.size(), clean, nameDrift, stale, unknown, rows, gaps);
  }

  /** The fifth finding: active here, but the terminal calls them something else. */
  public static final String NAME_DRIFT = "NAME_DRIFT";

  /**
   * Whether the roster and the terminal disagree about who a pin is.
   *
   * <p>Case and surrounding whitespace are ignored, because "ANIL KUMAR" and "Anil Kumar" are the
   * same person and flagging that would bury the drift that matters. A blank on either side is not
   * drift either: an unnamed roster row is a gap somebody is already working on, and a terminal that
   * reports no name is telling us nothing to disagree with.
   */
  static boolean namesDisagree(String roster, String onDevice) {
    if (roster == null || roster.isBlank() || onDevice == null || onDevice.isBlank()) {
      return false;
    }
    return !roster.trim().equalsIgnoreCase(onDevice.trim());
  }

  private static final class Inventory {
    private final TreeSet<Integer> fingers = new TreeSet<>();
    private boolean face;
  }

  // ------------------------------------------------------------------ acting

  /**
   * Removes the selected pins from THIS terminal's register.
   *
   * <p>Registers only. No roster row is touched and no punch is deleted — the history of somebody
   * having worked here is not what is wrong, and erasing it would destroy the evidence that explains
   * the attendance already recorded against them.
   *
   * <p>The active-person guard in the command service still stands underneath: a pin belonging to
   * somebody active at this building is refused, whatever this screen selected.
   */
  @Transactional
  public int deleteFromRegister(String deviceId, List<String> pins, String actorId) {
    IclockDevice device = devices.findById(deviceId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Terminal not found"));
    int queued = 0;
    for (String raw : pins) {
      String pin = IclockPin.canonicalOrNull(raw);
      if (pin == null) {
        continue;
      }
      commands.queueUserDelete(device.getId(), pin, actorId);
      queued++;
    }
    log.info("iclock: register cleanup queued {} deletion(s) on {}", queued, device.getSerialNumber());
    return queued;
  }

  /**
   * Marks a still-arriving dump as settled.
   *
   * <p>Runs from the ingest path, in its own transaction, so a bookkeeping failure never costs a
   * device push.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void noteDumpArrived(String serialNumber) {
    String deviceId = devices.findBySerialNumber(serialNumber)
        .filter(d -> "CLAIMED".equals(d.getStatus()))
        .map(IclockDevice::getId)
        .orElse(null);
    if (deviceId == null) {
      return;
    }
    audits.findFirstByDeviceIdAndStatusOrderByRequestedAtDesc(deviceId, IclockRegisterAudit.REQUESTED)
        .ifPresent(a -> {
          a.setStatus(IclockRegisterAudit.RECEIVED);
          a.setCompletedAt(Instant.now());
          audits.save(a);
        });
  }
}
