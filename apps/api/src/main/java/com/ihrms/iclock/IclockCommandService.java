package com.ihrms.iclock;

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

  private IclockDeviceCommand queue(
      IclockDevice device, String kind, String payload,
      String personId, String pin, String actorId) {
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

  /** One person's line in a bulk name sync, decided before anything is queued. */
  public record NameSyncRow(String personId, String pin, String name, String outcome, String detail) {}

  /** What a building-wide name sync would do, or did. */
  public record NameSyncReport(
      boolean committed, int people, int skipped, int devices, int commandsQueued,
      List<NameSyncRow> rows) {}

  /**
   * What a building-wide name push WOULD do. Structurally incapable of doing it.
   *
   * <p>Separate entry point rather than a boolean, per the standing rule, and
   * {@code readOnly = true} puts Hibernate in FlushMode.MANUAL so a stray mutation cannot reach the
   * database. The stakes are higher here than for a roster preview: the committed version writes to
   * every screen in the building.
   */
  @Transactional(readOnly = true)
  public NameSyncReport previewNameSync(String siteId) {
    return decideNameSync(siteId, false, null);
  }

  /**
   * Queues a name push for everybody at a building.
   *
   * <p>This is the pass that fixes the slug and register-junk names in one go. It is one command per
   * person PER DEVICE, so a two-terminal building doubles the count — the number is reported rather
   * than left to be discovered when the queue looks unexpectedly long.
   */
  @Transactional
  public NameSyncReport syncNames(String siteId, String actorId) {
    return decideNameSync(siteId, true, actorId);
  }

  private NameSyncReport decideNameSync(String siteId, boolean commit, String actorId) {
    List<IclockDevice> targets = claimedDevicesAt(siteId);
    if (targets.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "No claimed terminal at this building to push to.");
    }
    List<NameSyncRow> rows = new ArrayList<>();
    int people = 0, skipped = 0, queued = 0;

    for (IclockPerson person : people_findActive(siteId)) {
      if (person.getName() == null || person.getName().isBlank()) {
        // Pushing an empty name would blank the screen — worse than the slug it replaces.
        skipped++;
        rows.add(new NameSyncRow(person.getId(), person.getPin(), null, "SKIPPED",
            "no name on the roster yet"));
        continue;
      }
      String onWire = IclockCommandDialect.sanitiseName(person.getName());
      people++;
      if (commit) {
        for (IclockDevice device : targets) {
          queue(device, "UPDATE_USERINFO",
              IclockCommandDialect.updateUserInfo(person.getPin(), person.getName()),
              person.getId(), person.getPin(), actorId);
          queued++;
        }
      } else {
        queued += targets.size();
      }
      rows.add(new NameSyncRow(person.getId(), person.getPin(), onWire,
          commit ? "QUEUED" : "WOULD_QUEUE",
          onWire.equals(person.getName().trim()) ? null
              : "shortened for the device screen from: " + person.getName().trim()));
    }
    return new NameSyncReport(commit, people, skipped, targets.size(), queued, rows);
  }

  private List<IclockPerson> people_findActive(String siteId) {
    return people.findBySiteIdAndActiveTrue(siteId);
  }

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
  public void recordAck(String commandId, String returnValue, String rawBody) {
    if (commandId == null || commandId.isBlank()) {
      log.warn("iclock: devicecmd ack with no command id; body captured raw: {}", rawBody);
      return;
    }
    IclockDeviceCommand c = commands.findById(commandId).orElse(null);
    if (c == null) {
      log.warn("iclock: devicecmd ack for unknown command {}; body: {}", commandId, rawBody);
      return;
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
  }

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
