package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read models for the Time &amp; Attendance console: Overview, Live Board and Person Day View.
 *
 * <p><b>Everything here is shift-aware.</b> The org's day runs 19:00 → 04:00 IST, so at 02:00 the
 * "current" shift-day is the one that began at 19:00 YESTERDAY. Using the calendar date would put
 * half of every night shift on the wrong day, which is the single easiest way to make an attendance
 * board quietly wrong.
 */
@Service
public class IclockBoardService {

  /** Where a person currently is, derived from their last effective punch. */
  public enum Presence {
    IN_OFFICE,
    IN_CAFETERIA,
    LEFT,
    NOT_ARRIVED
  }

  public record PersonChip(
      String personId, String name, String pin, String companyName, String team,
      Presence presence, Instant lastAt, String lastDirection, String lastArea) {}

  public record DeviceHealth(
      String serialNumber, String name, String direction, String status,
      Instant lastSeenAt, long minutesSinceSeen, boolean healthy, long punchesToday) {}

  public record Overview(
      String siteId, String siteName, LocalDate shiftDate,
      long rosterSize, long presentNow, long arrived, long notArrived, long lateSoFar,
      long punchesToday, List<DeviceHealth> devices) {}

  public record Board(
      String siteId, LocalDate shiftDate, Instant asOf,
      List<PersonChip> inOffice, List<PersonChip> inCafeteria,
      List<PersonChip> left, List<PersonChip> notArrived) {}

  public record DayPunch(
      String id, Instant effectiveAt, String direction, String area,
      int burstCount, Instant burstFirstAt, Instant burstLastAt, String anomaly) {}

  public record DaySession(Instant from, Instant to, boolean estimated, boolean cafeteria) {}

  public record PersonDay(
      String personId, String name, String pin, LocalDate shiftDate,
      Instant firstIn, Instant lastOut, List<DayPunch> punches, List<DaySession> sessions) {}

  private final IclockPersonRepository people;
  private final IclockPunchRepository punches;
  private final IclockDeviceRepository devices;
  private final IclockSiteRepository sites;
  private final CompanyRepository companies;

  public IclockBoardService(
      IclockPersonRepository people,
      IclockPunchRepository punches,
      IclockDeviceRepository devices,
      IclockSiteRepository sites,
      CompanyRepository companies) {
    this.people = people;
    this.punches = punches;
    this.devices = devices;
    this.sites = sites;
    this.companies = companies;
  }

  /** A device is considered healthy if it has checked in within three poll intervals. */
  private static final long HEALTHY_MINUTES = 3;

  @Transactional(readOnly = true)
  public Overview overview(String siteId) {
    var site = sites.findById(siteId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
    ZoneId zone = zoneOf(site.getTimezone());
    LocalDate shiftDate = IclockShiftDay.of(Instant.now(), zone);

    List<IclockPerson> roster = people.findBySiteIdAndActiveTrue(siteId);
    Map<String, PersonChip> chips = chipsFor(siteId, roster, shiftDate);

    long present = chips.values().stream().filter(c -> c.presence() == Presence.IN_OFFICE
        || c.presence() == Presence.IN_CAFETERIA).count();
    long arrived = chips.values().stream().filter(c -> c.presence() != Presence.NOT_ARRIVED).count();
    long late = lateCount(siteId, shiftDate, zone);

    List<DeviceHealth> health = new ArrayList<>();
    Instant now = Instant.now();
    for (IclockDevice d : devices.findBySiteId(siteId)) {
      long mins = d.getLastSeenAt() == null
          ? Long.MAX_VALUE
          : java.time.Duration.between(d.getLastSeenAt(), now).toMinutes();
      health.add(new DeviceHealth(
          d.getSerialNumber(), d.getName(), d.getDirection(), d.getStatus(), d.getLastSeenAt(),
          mins == Long.MAX_VALUE ? -1 : mins, mins <= HEALTHY_MINUTES,
          // Per DEVICE, not per site. This used to pass the site-wide count inside the per-device
          // loop, so every terminal claimed the whole building's traffic as its own.
          punches.countByDeviceIdAndShiftDate(d.getId(), shiftDate)));
    }

    return new Overview(
        siteId, site.getName(), shiftDate, roster.size(), present, arrived,
        roster.size() - arrived, late,
        punches.countBySiteIdAndShiftDate(siteId, shiftDate), health);
  }

  @Transactional(readOnly = true)
  public Board board(String siteId) {
    var site = sites.findById(siteId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
    ZoneId zone = zoneOf(site.getTimezone());
    LocalDate shiftDate = IclockShiftDay.of(Instant.now(), zone);

    List<IclockPerson> roster = people.findBySiteIdAndActiveTrue(siteId);
    Map<String, PersonChip> chips = chipsFor(siteId, roster, shiftDate);

    List<PersonChip> inOffice = new ArrayList<>();
    List<PersonChip> inCafeteria = new ArrayList<>();
    List<PersonChip> left = new ArrayList<>();
    List<PersonChip> notArrived = new ArrayList<>();
    for (PersonChip c : chips.values()) {
      switch (c.presence()) {
        case IN_OFFICE -> inOffice.add(c);
        case IN_CAFETERIA -> inCafeteria.add(c);
        case LEFT -> left.add(c);
        case NOT_ARRIVED -> notArrived.add(c);
      }
    }
    // NEWEST FIRST within every column, per the recency addendum: the latest punch sits at the top, so
    // the board reads as a feed of what just happened rather than as a roster in pin order — which is
    // what it was, because chips are emitted in roster order and nothing re-sorted them.
    //
    // NOT_ARRIVED has no punch by definition, so lastAt is null for all of them and recency cannot
    // order that column; it falls through to name, which is the only useful order for a list whose
    // whole content is "people who are missing".
    inOffice.sort(NEWEST_FIRST);
    inCafeteria.sort(NEWEST_FIRST);
    left.sort(NEWEST_FIRST);
    notArrived.sort(BY_NAME);

    return new Board(siteId, shiftDate, Instant.now(), inOffice, inCafeteria, left, notArrived);
  }

  /** Latest punch at the top; a chip with no punch sorts last rather than first. */
  private static final java.util.Comparator<PersonChip> NEWEST_FIRST =
      java.util.Comparator.comparing(
              PersonChip::lastAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
          .thenComparing(c -> c.name() == null ? "￿" : c.name().toLowerCase(java.util.Locale.ROOT));

  /** Unnamed people sort to the bottom: they are a cleanup task, not the headline. */
  private static final java.util.Comparator<PersonChip> BY_NAME =
      java.util.Comparator.comparing(
              (PersonChip c) -> c.name() == null ? "￿" : c.name().toLowerCase(java.util.Locale.ROOT))
          .thenComparing(PersonChip::pin);

  @Transactional(readOnly = true)
  public PersonDay personDay(String personId, String shiftDateOrNull) {
    IclockPerson person = people.findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
    var site = sites.findById(person.getSiteId()).orElseThrow();
    ZoneId zone = zoneOf(site.getTimezone());
    LocalDate day = shiftDateOrNull == null || shiftDateOrNull.isBlank()
        ? IclockShiftDay.of(Instant.now(), zone)
        : LocalDate.parse(shiftDateOrNull);

    List<IclockPunch> rows = punches.findByPersonIdAndShiftDateOrderByEffectiveAtAsc(personId, day);
    List<DayPunch> dayPunches = rows.stream()
        .map(p -> new DayPunch(p.getId(), p.getEffectiveAt(), p.getDirection(), p.getArea(),
            p.getBurstCount(), p.getBurstFirstAt(), p.getBurstLastAt(), p.getAnomaly()))
        .toList();

    // Sessions are PAIRED BY INFERENCE, not asserted — an unpaired IN is flagged estimated rather
    // than silently closed, because a missing OUT is normal in an event stream and pretending
    // otherwise is how a board starts lying.
    List<DaySession> sessions = new ArrayList<>();
    Instant openFrom = null;
    boolean openCafeteria = false;
    for (IclockPunch p : rows) {
      boolean cafe = "CAFETERIA".equals(p.getArea());
      if ("IN".equals(p.getDirection())) {
        if (openFrom != null) {
          sessions.add(new DaySession(openFrom, null, true, openCafeteria)); // unpaired
        }
        openFrom = p.getEffectiveAt();
        openCafeteria = cafe;
      } else if ("OUT".equals(p.getDirection())) {
        if (openFrom != null) {
          sessions.add(new DaySession(openFrom, p.getEffectiveAt(), false, openCafeteria));
          openFrom = null;
        } else {
          sessions.add(new DaySession(null, p.getEffectiveAt(), true, cafe)); // OUT with no IN
        }
      }
    }
    if (openFrom != null) {
      sessions.add(new DaySession(openFrom, null, true, openCafeteria));
    }

    Instant firstIn = rows.stream().filter(p -> "IN".equals(p.getDirection()))
        .map(IclockPunch::getEffectiveAt).findFirst().orElse(null);
    Instant lastOut = rows.stream().filter(p -> "OUT".equals(p.getDirection()))
        .map(IclockPunch::getEffectiveAt).reduce((a, b) -> b).orElse(null);

    return new PersonDay(personId, person.getName(), person.getPin(), day,
        firstIn, lastOut, dayPunches, sessions);
  }

  // ------------------------------------------------------------------ inner

  private Map<String, PersonChip> chipsFor(
      String siteId, List<IclockPerson> roster, LocalDate shiftDate) {
    // Company names, resolved in ONE query for the whole roster.
    //
    // This field used to be passed as a literal null on every chip, which meant the Live Board carried
    // no company at all — and the console's requirement to GROUP the board by company was therefore a
    // backend gap, not a presentation one. Batched rather than per-person because a 212-person roster
    // would otherwise be 212 lookups on the screen that refreshes every 30 seconds.
    Set<String> companyIds =
        roster.stream()
            .map(IclockPerson::getCompanyId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    Map<String, String> companyNames =
        companyIds.isEmpty()
            ? Map.of()
            : companies.findAllById(companyIds).stream()
                .collect(java.util.stream.Collectors.toMap(Company::getId, Company::getName));

    Map<String, IclockPunch> lastByPerson = new LinkedHashMap<>();
    // Ordered newest-first, so the FIRST row seen per person is their latest punch.
    for (IclockPunch p : punches.findBySiteIdAndShiftDateOrderByEffectiveAtDesc(siteId, shiftDate)) {
      if (p.getPersonId() != null) {
        lastByPerson.putIfAbsent(p.getPersonId(), p);
      }
    }
    Map<String, PersonChip> out = new LinkedHashMap<>();
    for (IclockPerson person : roster) {
      IclockPunch last = lastByPerson.get(person.getId());
      Presence presence;
      if (last == null) {
        presence = Presence.NOT_ARRIVED;
      } else if ("CAFETERIA".equals(last.getArea())) {
        // A cafeteria punch means they are in the building, just not at their desk.
        presence = "OUT".equals(last.getDirection()) ? Presence.IN_OFFICE : Presence.IN_CAFETERIA;
      } else {
        presence = "IN".equals(last.getDirection()) ? Presence.IN_OFFICE : Presence.LEFT;
      }
      // companyLabel is the fallback: a person whose seed label never matched an IHRMS company still
      // belongs somewhere on the board, and "Combino IT (unmatched)" groups better than "no company".
      String companyName =
          person.getCompanyId() == null
              ? person.getCompanyLabel()
              : companyNames.getOrDefault(person.getCompanyId(), person.getCompanyLabel());
      out.put(person.getId(), new PersonChip(
          person.getId(), person.getName(), person.getPin(), companyName, person.getTeam(),
          presence, last == null ? null : last.getEffectiveAt(),
          last == null ? null : last.getDirection(),
          last == null ? null : last.getArea()));
    }
    return out;
  }

  /** People whose first gate IN was after the 19:15 threshold. lateExemptMin is a P2 input. */
  private long lateCount(String siteId, LocalDate shiftDate, ZoneId zone) {
    Instant threshold = ShiftConfig.lateThreshold(shiftDate);
    Map<String, Instant> firstIn = new LinkedHashMap<>();
    for (IclockPunch p : punches.findBySiteIdAndShiftDateOrderByEffectiveAtDesc(siteId, shiftDate)) {
      if (p.getPersonId() != null && "IN".equals(p.getDirection()) && "GATE".equals(p.getArea())) {
        firstIn.put(p.getPersonId(), p.getEffectiveAt()); // newest-first, so the last write is earliest
      }
    }
    return firstIn.values().stream().filter(t -> t.isAfter(threshold)).count();
  }

  private static ZoneId zoneOf(String tz) {
    try {
      return ZoneId.of(tz);
    } catch (Exception e) {
      return ShiftConfig.ZONE;
    }
  }
}
