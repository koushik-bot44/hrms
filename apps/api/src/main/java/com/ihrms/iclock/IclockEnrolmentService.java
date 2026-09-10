package com.ihrms.iclock;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockDeviceUserName;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockDeviceUserNameRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds roster rows from what a terminal says about its own enrolments.
 *
 * <p><b>THE DIRECTION OF TRUTH IS ONE-WAY.</b> The console is master. A device enrolment may CREATE a
 * person who does not exist yet, so their punches attribute from the first tap instead of piling up
 * in the unmapped-pin inbox — but it may never overwrite a name a human has entered. These registers
 * are the source of the slug names P3 exists to fix; letting them write back over corrected names
 * would undo the name push on the next enrolment and leave the two halves of P3 fighting.
 *
 * <p>Where the device disagrees with an existing roster row, the difference is SURFACED rather than
 * applied: it is information — somebody re-enrolled under a different name — and a suggestion an
 * operator can accept, not a fact.
 */
@Service
public class IclockEnrolmentService {

  private static final Logger log = LoggerFactory.getLogger(IclockEnrolmentService.class);

  /**
   * Records the terminal's own label for a pin.
   *
   * <p>Stored raw. A name differing only by trimming is a different finding from a name that is
   * somebody else entirely, and normalising here would collapse the two.
   */
  private void rememberDeviceName(String deviceId, IclockUserInfo.Record r) {
    IclockDeviceUserName row = deviceNames.findByDeviceIdAndPin(deviceId, r.pin())
        .orElseGet(IclockDeviceUserName::new);
    row.setDeviceId(deviceId);
    row.setPin(r.pin());
    row.setDeviceName(r.name());
    row.setPrivilege(r.privilege());
    row.setSeenAt(java.time.Instant.now());
    deviceNames.save(row);
  }

  /** Marks a person the system invented from a terminal rather than from an import or an operator. */
  static final String FROM_DEVICE = "device-enrolment";

  private final IclockPersonRepository people;
  private final IclockDeviceRepository devices;
  private final IclockDeviceUserNameRepository deviceNames;

  public IclockEnrolmentService(
      IclockPersonRepository people,
      IclockDeviceRepository devices,
      IclockDeviceUserNameRepository deviceNames) {
    this.people = people;
    this.devices = devices;
    this.deviceNames = deviceNames;
  }

  /** What one USERINFO push did. Counted so the ingest log can say so without a row-by-row dump. */
  public record SeedResult(int records, int created, int suggestions, int ignored) {}

  /**
   * Applies a USERINFO push from one terminal.
   *
   * <p>Runs in its own transaction, like promotion, and for the same reason: this happens inside a
   * device request, and nothing here may cost the push. A terminal that gets an error instead of an
   * acknowledgement retries the batch forever.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public SeedResult apply(String serialNumber, String body) {
    List<IclockUserInfo.Record> records = IclockUserInfo.parse(body);
    if (records.isEmpty()) {
      return new SeedResult(0, 0, 0, 0);
    }
    IclockDevice device = devices.findBySerialNumber(serialNumber).orElse(null);
    if (device == null || !"CLAIMED".equals(device.getStatus()) || device.getSiteId() == null) {
      // An unclaimed terminal has no building, so there is no roster for its users to belong to.
      // The push is still captured raw; it simply seeds nothing.
      return new SeedResult(records.size(), 0, 0, records.size());
    }

    int created = 0, suggestions = 0, ignored = 0;
    List<String> seeded = new ArrayList<>();
    for (IclockUserInfo.Record r : records) {
      // WHAT THE TERMINAL CALLS THEM, kept verbatim and separately from the roster name. This is the
      // input NAME DRIFT was wrongly declared impossible for: it was always arriving, 141 records in
      // a single query, and an evidence query with a LIMIT hid them.
      rememberDeviceName(device.getId(), r);
      Optional<IclockPerson> existing = people.findBySiteIdAndPin(device.getSiteId(), r.pin());

      if (existing.isPresent()) {
        // ROSTER STAYS MASTER. A device name never overwrites one a human entered; a difference is
        // worth knowing about, and knowing is what the log is for until the console surfaces it.
        IclockPerson person = existing.get();
        if (IclockUserInfo.isUsableName(r.pin(), r.name())
            && !r.name().trim().equalsIgnoreCase(
                person.getName() == null ? "" : person.getName().trim())) {
          suggestions++;
          log.info("iclock: {} calls pin {} '{}' but the roster says '{}' — roster kept",
              serialNumber, r.pin(), r.name(), person.getName());
        }
        continue;
      }

      if (!IclockUserInfo.isUsableName(r.pin(), r.name())) {
        // Seeding a numeric or placeholder name is worse than leaving the person unnamed: an unnamed
        // row shows in the console as work-to-do, a junk-named one looks finished.
        ignored++;
        continue;
      }

      // NOTE ON PRIVILEGE: the device's Pri field is read past, never stored and never echoed. A
      // terminal reporting somebody as an administrator is reporting a physical act performed at the
      // device menu; this system neither tracks that nor is capable of granting it.
      IclockPerson person = new IclockPerson();
      person.setSiteId(device.getSiteId());
      person.setPin(r.pin());
      person.setName(r.name().trim());
      person.setActive(true);
      // Company and team are deliberately left blank. The device knows neither, and inventing them
      // would put somebody in a company's report on the strength of a terminal's guess.
      person.setRole(FROM_DEVICE);
      people.save(person);
      created++;
      seeded.add(r.pin());
    }

    if (created > 0) {
      log.info("iclock: {} seeded {} roster row(s) from enrolment: {}", serialNumber, created, seeded);
    }
    return new SeedResult(records.size(), created, suggestions, ignored);
  }
}
