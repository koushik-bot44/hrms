package com.ihrms.iclock;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunchMember;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchMemberRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotes a RAW punch into an effective, attributed one — the P1a pipeline.
 *
 * <pre>
 *   raw punch
 *     → device must be CLAIMED (site + area + direction)
 *     → interpret the wall-clock string in the SITE's timezone
 *     → canonical pin → employee, scoped to the site
 *     → employee must have been active AT punchedAt   (D4, time-aware)
 *     → burst-collapse into an effective row
 * </pre>
 *
 * <p><b>Nothing here can cost a raw punch.</b> Promotion runs in its own transaction and every failure
 * mode simply declines to promote; the raw row is already committed and the device has already been
 * told OK. Unpromotable punches stay raw-only and surface in the operator inbox.
 *
 * <p><b>Order independence.</b> Burst aggregates are computed with min/max rather than by assuming
 * arrival order, and the candidate window is two-sided. This is not theoretical: this fleet buffered
 * ~10 minutes of punches during a DNS outage and flushed them in one batch, so {@code receivedAt}
 * order did not match {@code punchedAt} order. Processing the same set in any order yields the same
 * result.
 */
@Service
public class IclockPromotionService {

  private static final Logger log = LoggerFactory.getLogger(IclockPromotionService.class);

  /** The device's wall-clock format, e.g. {@code 2026-08-26 03:49:06}. */
  private static final DateTimeFormatter DEVICE_TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  /** Why a raw punch did or did not become an effective punch. */
  public enum Outcome {
    PROMOTED_NEW,
    PROMOTED_JOINED,
    /** Already absorbed into a burst — the idempotent no-op. */
    ALREADY_PROMOTED,
    DEVICE_UNCLAIMED,
    UNPARSEABLE_TIME,
    NO_PIN,
    UNKNOWN_PIN,
    /** Punched after their last working day (D4) — surfaced, never silently attributed. */
    OFFBOARDED,
    /** The roster person exists but is marked inactive — surfaced, not attributed. */
    INACTIVE_PERSON;

    public boolean promoted() {
      return this == PROMOTED_NEW || this == PROMOTED_JOINED;
    }
  }

  private final IclockRawPunchRepository rawPunches;
  private final IclockDeviceRepository devices;
  private final IclockSiteRepository sites;
  private final IclockPersonRepository people;
  private final IclockPunchRepository punches;
  private final IclockPunchMemberRepository members;
  private final EmployeeRepository employees;
  private final OffboardingCaseRepository offboarding;
  /** Owns the shift PROFILES: which shift a person works decides which shift-day their punch files on. */
  private final IclockSitePolicyService policies;
  private final IclockProperties props;

  public IclockPromotionService(
      IclockRawPunchRepository rawPunches,
      IclockDeviceRepository devices,
      IclockSiteRepository sites,
      IclockPersonRepository people,
      IclockPunchRepository punches,
      IclockPunchMemberRepository members,
      EmployeeRepository employees,
      OffboardingCaseRepository offboarding,
      IclockSitePolicyService policies,
      IclockProperties props) {
    this.policies = policies;
    this.rawPunches = rawPunches;
    this.devices = devices;
    this.sites = sites;
    this.people = people;
    this.punches = punches;
    this.members = members;
    this.employees = employees;
    this.offboarding = offboarding;
    this.props = props;
  }

  /**
   * Promotes one raw punch. Idempotent: a punch already absorbed into a burst returns
   * {@link Outcome#ALREADY_PROMOTED} and writes nothing.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Outcome promote(String rawPunchId) {
    if (members.existsByRawPunchId(rawPunchId)) {
      return Outcome.ALREADY_PROMOTED;
    }
    IclockRawPunch raw = rawPunches.findById(rawPunchId).orElse(null);
    if (raw == null) {
      return Outcome.ALREADY_PROMOTED; // nothing to do; treat as a no-op rather than an error
    }

    IclockDevice device = resolveDevice(raw);
    if (device == null || !"CLAIMED".equals(device.getStatus())) {
      return Outcome.DEVICE_UNCLAIMED;
    }
    IclockSite site = sites.findById(device.getSiteId()).orElse(null);
    if (site == null) {
      return Outcome.DEVICE_UNCLAIMED;
    }
    ZoneId zone = zoneOf(site);

    Instant punchedAt = parseDeviceTime(raw.getPunchedAtRaw(), zone);
    if (punchedAt == null) {
      return Outcome.UNPARSEABLE_TIME;
    }

    String pin = IclockPin.canonicalOrNull(raw.getDevicePin());
    if (pin == null) {
      return Outcome.NO_PIN;
    }
    // THE resolution step, identity-first: the roster is the sole source. An IHRMS employee is
    // optional enrichment, so a person with no employee link resolves and attributes normally.
    Optional<IclockPerson> found = people.findBySiteIdAndPin(site.getId(), pin);
    if (found.isEmpty()) {
      return Outcome.UNKNOWN_PIN;
    }
    IclockPerson person = found.get();
    if (!person.isActive()) {
      return Outcome.INACTIVE_PERSON;
    }
    // D4 still applies, but ONLY where an employee link exists to judge against. An unlinked person
    // has no offboarding record, and refusing their punch on that basis would decline the majority.
    if (person.getEmployeeId() != null) {
      Employee employee = employees.findById(person.getEmployeeId()).orElse(null);
      if (employee != null && !wasActiveAt(employee, punchedAt, zone)) {
        return Outcome.OFFBOARDED;
      }
    }

    try {
      return collapse(raw, device, site, zone, person, pin, punchedAt);
    } catch (DataIntegrityViolationException race) {
      // Another thread promoted the same raw punch or created the same anchor concurrently. The
      // member unique index is the arbiter; losing the race is a no-op, not an error.
      log.debug("iclock: promotion race on raw punch {}", rawPunchId);
      return Outcome.ALREADY_PROMOTED;
    }
  }

  // ------------------------------------------------------------------ burst

  private Outcome collapse(
      IclockRawPunch raw,
      IclockDevice device,
      IclockSite site,
      ZoneId zone,
      IclockPerson person,
      String pin,
      Instant punchedAt) {

    String direction = device.getDirection();
    String area = device.getArea();

    // THE PERSON'S OWN SHIFT decides which shift-day this punch files under — resolved once here and
    // threaded down, rather than each writer reaching for a global constant. A day-shift person's
    // 09:00 arrival and 18:00 departure belong to the SAME shift-day; under the night cut they land on
    // consecutive ones and the pair never closes.
    IclockShiftProfile profile =
        policies.effective(site.getId()).profileFor(person.getShiftProfile());

    // MIXED carries no usable direction, so collapsing would invent one. Each punch stands alone and
    // is flagged. No such device exists on this fleet; the branch is unit-tested regardless.
    if ("MIXED".equals(direction)) {
      createBurst(raw, device, site, zone, person, pin, punchedAt, "MIXED_NO_COLLAPSE", profile);
      return Outcome.PROMOTED_NEW;
    }

    IclockPunch candidate =
        punches
            .findBurstCandidate(device.getId(), pin, punchedAt, props.burstWindowSeconds())
            .orElse(null);

    if (candidate != null && !chainBroken(candidate, person, area, device.getId(), punchedAt)) {
      joinBurst(candidate, raw, zone, punchedAt, direction, profile);
      return Outcome.PROMOTED_JOINED;
    }
    createBurst(raw, device, site, zone, person, pin, punchedAt, null, profile);
    return Outcome.PROMOTED_NEW;
  }

  /**
   * True when a punch on ANOTHER device in the same area falls between the burst and this punch — the
   * person went somewhere else in between, so this is a new presentation, not a re-tap.
   *
   * <p>Same-area only: a CAFETERIA punch must never break a GATE burst.
   */
  private boolean chainBroken(
      IclockPunch burst, IclockPerson person, String area, String deviceId, Instant punchedAt) {
    Instant from = punchedAt.isBefore(burst.getBurstFirstAt()) ? punchedAt : burst.getBurstLastAt();
    Instant to = punchedAt.isBefore(burst.getBurstFirstAt()) ? burst.getBurstFirstAt() : punchedAt;
    if (!from.isBefore(to)) {
      return false;
    }
    return punches.existsInterveningPunch(person.getId(), area, deviceId, from, to);
  }

  private void createBurst(
      IclockRawPunch raw,
      IclockDevice device,
      IclockSite site,
      ZoneId zone,
      IclockPerson person,
      String pin,
      Instant punchedAt,
      String anomaly,
      IclockShiftProfile profile) {
    IclockPunch p = new IclockPunch();
    p.setRawPunchId(raw.getId());
    p.setEffectiveRawPunchId(raw.getId());
    p.setDeviceId(device.getId());
    p.setSiteId(site.getId());
    // Identity comes from the roster; company/employee are enrichment and may legitimately be null.
    p.setPersonId(person.getId());
    p.setCompanyId(person.getCompanyId());
    p.setEmployeeId(person.getEmployeeId());
    p.setDevicePin(pin);
    p.setPunchedAt(punchedAt);
    p.setEffectiveAt(punchedAt);
    p.setShiftDate(profile.shiftDateOf(punchedAt, zone));
    p.setArea(device.getArea());
    p.setDirection(device.getDirection());
    p.setBurstFirstAt(punchedAt);
    p.setBurstLastAt(punchedAt);
    p.setBurstCount(1);
    p.setAnomaly(anomaly);
    IclockPunch saved = punches.saveAndFlush(p);
    addMember(saved.getId(), raw.getId());
  }

  /**
   * Absorbs a punch into an existing burst and updates the effective row IN PLACE, so live visibility
   * is immediate — the row exists from the first punch and sharpens as the burst extends. There is no
   * settling delay and no deferred pass.
   *
   * <p>Aggregates use min/max, so the outcome does not depend on the order punches are processed in.
   */
  private void joinBurst(
      IclockPunch burst,
      IclockRawPunch raw,
      ZoneId zone,
      Instant punchedAt,
      String direction,
      IclockShiftProfile profile) {
    if (punchedAt.isBefore(burst.getBurstFirstAt())) {
      burst.setBurstFirstAt(punchedAt);
    }
    if (punchedAt.isAfter(burst.getBurstLastAt())) {
      burst.setBurstLastAt(punchedAt);
    }
    burst.setBurstCount(burst.getBurstCount() + 1);

    // IN keeps the FIRST punch of the burst, OUT keeps the LATEST. Both are expressed as a comparison
    // against the current effective instant rather than as "the newest wins", which is what makes an
    // out-of-order flush produce the same answer as live arrival.
    boolean takesOver =
        "IN".equals(direction)
            ? punchedAt.isBefore(burst.getEffectiveAt())
            : punchedAt.isAfter(burst.getEffectiveAt());
    if (takesOver) {
      burst.setEffectiveRawPunchId(raw.getId());
      burst.setEffectiveAt(punchedAt);
      // Shift-day always follows the KEPT punch, so a burst straddling the cut is attributed to the
      // day it actually counts for — and the cut is the one belonging to THIS person's shift.
      burst.setShiftDate(profile.shiftDateOf(punchedAt, zone));
    }
    punches.saveAndFlush(burst);
    addMember(burst.getId(), raw.getId());
  }

  private void addMember(String punchId, String rawPunchId) {
    IclockPunchMember m = new IclockPunchMember();
    m.setPunchId(punchId);
    m.setRawPunchId(rawPunchId);
    members.saveAndFlush(m);
  }

  // ------------------------------------------------------------- resolution

  private IclockDevice resolveDevice(IclockRawPunch raw) {
    if (raw.getDeviceId() != null) {
      IclockDevice byId = devices.findById(raw.getDeviceId()).orElse(null);
      if (byId != null) {
        return byId;
      }
    }
    // deviceId is nullable by design (a lost upsert race must not cost a punch), so fall back to the
    // durable identity.
    return devices.findBySerialNumber(raw.getSerialNumber()).orElse(null);
  }

  /**
   * D4 — time-aware activity. An employee is resolvable for a punch if they were active WHEN IT
   * HAPPENED, not merely active now.
   *
   * <p>A completed offboarding carries a {@code lastWorkingDay}; punches up to and including that day
   * are legitimate and attribute normally. Anything after it declines to promote and surfaces in the
   * inbox, because recording a departed employee at the gate is worse than leaving a punch
   * unattributed for a human to look at.
   */
  private boolean wasActiveAt(Employee employee, Instant punchedAt, ZoneId zone) {
    if (employee.getStatus() == EmployeeStatus.APPROVED) {
      return true;
    }
    if (employee.getStatus() != EmployeeStatus.OFFBOARDED) {
      // INVITED / IN_PROGRESS / SUBMITTED / REVISION_REQUESTED / HR_VERIFIED / REJECTED — never a
      // valid gate identity. A pin should not have been mapped to them in the first place.
      return false;
    }
    LocalDate punchDay = punchedAt.atZone(zone).toLocalDate();
    return offboarding
        .findFirstByEmployeeIdAndStatusIn(employee.getId(), List.of(OffboardingStatus.COMPLETED))
        .map(c -> !punchDay.isAfter(c.getLastWorkingDay()))
        // OFFBOARDED with no completed case is inconsistent data; refuse rather than guess.
        .orElse(false);
  }

  static Instant parseDeviceTime(String raw, ZoneId zone) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(raw.trim(), DEVICE_TIME).atZone(zone).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private ZoneId zoneOf(IclockSite site) {
    try {
      return ZoneId.of(site.getTimezone());
    } catch (Exception e) {
      log.warn("iclock: site {} has an invalid timezone '{}'", site.getId(), site.getTimezone());
      return com.ihrms.attendance.ShiftConfig.ZONE;
    }
  }
}
