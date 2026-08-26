package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockSite;
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
      /** The building this person belongs to. Carried so the board can group by it across a fleet. */
      String siteName,
      /**
       * NIGHT or DAY. Carried because it explains the row: whether someone counts as late, which day
       * their punches file under and when their break alerts may fire all follow from it, and an
       * operator looking at a board with two shifts on it needs to see which one they are reading.
       */
      String shiftProfile,
      Presence presence, Instant lastAt, String lastDirection, String lastArea,
      /**
       * Overlay flag for the "exceeding break" lens. The person stays in their canonical presence
       * column — this only lets that column show a matching amber badge, so the alert strip and the
       * columns cannot disagree about who is overdue.
       */
      boolean breakAlert) {}

  /**
   * Someone who arrived on the last completed shift day and never tapped out at the gate.
   *
   * <p>Surfacing only. No OUT is invented and nothing is mutated — the day view already renders the
   * unpaired session as an honest gap, and regularisation is P3's.
   */
  public record MissingOut(
      String personId, String name, String companyName, LocalDate shiftDate,
      Instant lastPunchAt, String lastDirection, String lastArea) {}

  /** One row of the "exceeding break" strip. Elapsed is recomputed per refresh, never stored. */
  public record BreakAlert(
      String personId, String name, String companyName,
      /** OUTSIDE or CAFETERIA. */
      String where,
      Instant since,
      long elapsedMinutes) {}

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
      List<PersonChip> left, List<PersonChip> notArrived,
      /**
       * People whose current absence has run past the break threshold. An OVERLAY: everyone here also
       * appears in their presence column. Empty outside shift hours by design.
       */
      List<BreakAlert> exceedingBreak,
      /**
       * People who arrived on the LAST COMPLETED shift day and never tapped out. A different day from
       * everything else on this payload, deliberately: it is a "yesterday needs attention" list, and
       * showing it for the live shift would flag everyone currently at their desk.
       */
      List<MissingOut> missingOut,
      /** The day {@link #missingOut} refers to, so the console can label it rather than guess. */
      LocalDate missingOutShiftDate) {}

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
      CompanyRepository companies,
      IclockSitePolicyService policies) {
    this.people = people;
    this.punches = punches;
    this.devices = devices;
    this.sites = sites;
    this.companies = companies;
    this.policies = policies;
  }

  /** A device is considered healthy if it has checked in within three poll intervals. */
  private static final long HEALTHY_MINUTES = 3;

  /**
   * "Exceeding break" thresholds. Simple config now; these fold into P2b's per-site policy tables when
   * those exist, which is why they are named for the rule rather than for a screen.
   */
  private final IclockSitePolicyService policies;

  @Transactional(readOnly = true)
  public Overview overview(String siteId) {
    var site = sites.findById(siteId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
    ZoneId zone = zoneOf(site.getTimezone());
    Instant asOf = Instant.now();

    IclockSitePolicyService.PolicyView policy = policies.effective(siteId);
    List<IclockPerson> roster = people.findBySiteIdAndActiveTrue(siteId);
    ShiftDays days = shiftDaysFor(roster, asOf, zone, policy);
    LocalDate shiftDate = days.defaultDay();
    Map<String, PersonChip> chips = chipsFor(siteId, site.getName(), roster, days, zone, policy);

    long present = chips.values().stream().filter(c -> c.presence() == Presence.IN_OFFICE
        || c.presence() == Presence.IN_CAFETERIA).count();
    long arrived = chips.values().stream().filter(c -> c.presence() != Presence.NOT_ARRIVED).count();
    long late = lateCount(siteId, roster, days, zone, policy);

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
          // loop, so every terminal claimed the whole building's traffic as its own. Counted across
          // every shift-day in play, because one gate serves both shifts.
          punches.countByDeviceIdAndShiftDateIn(d.getId(), days.all())));
    }

    return new Overview(
        siteId, site.getName(), shiftDate, roster.size(), present, arrived,
        roster.size() - arrived, late,
        punches.countBySiteIdAndShiftDateIn(siteId, days.all()), health);
  }

  @Transactional(readOnly = true)
  public Board board(String siteId) {
    var site = sites.findById(siteId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
    ZoneId zone = zoneOf(site.getTimezone());
    Instant asOf = Instant.now();

    IclockSitePolicyService.PolicyView policy = policies.effective(siteId);
    List<IclockPerson> roster = people.findBySiteIdAndActiveTrue(siteId);
    ShiftDays days = shiftDaysFor(roster, asOf, zone, policy);
    LocalDate shiftDate = days.defaultDay();
    Map<String, PersonChip> chips = chipsFor(siteId, site.getName(), roster, days, zone, policy);

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

    // The header's day. Individual Missing OUT rows carry their own, because a day-shift person's last
    // completed shift is not the same date as a night-shift person's.
    LocalDate completed = policy.night().completedShiftDay(asOf, zone);
    return new Board(siteId, shiftDate, asOf, inOffice, inCafeteria, left, notArrived,
        alertsFrom(chips.values(), zone, policy),
        missingOutFor(siteId, asOf, zone, policy), completed);
  }

  /**
   * People who arrived on {@code day} and never tapped out at the gate.
   *
   * <p>Ordered by last punch DESCENDING — the person who was last seen most recently is the one whose
   * exit is most likely simply un-tapped rather than genuinely unknown.
   */
  private List<MissingOut> missingOutFor(
      String siteId, Instant now, ZoneId zone, IclockSitePolicyService.PolicyView policy) {
    Map<String, IclockPerson> roster = new LinkedHashMap<>();
    for (IclockPerson person : people.findBySiteIdOrderByPinAsc(siteId)) {
      roster.put(person.getId(), person);
    }

    // WHICH DAY IS "LAST COMPLETED" DEPENDS ON THE SHIFT. The night shift's finished at 04:00; the day
    // shift's finishes at 18:00. Asking one question for both would either flag day-shift people who
    // are still at work or leave night-shift people out of the list until the following evening. Each
    // person is measured against their own last finished shift, and the row carries the date it used.
    Map<String, LocalDate> dayByPerson = new LinkedHashMap<>();
    Set<LocalDate> allDays = new java.util.LinkedHashSet<>();
    for (IclockPerson person : roster.values()) {
      LocalDate completed = policy.profileFor(person.getShiftProfile()).completedShiftDay(now, zone);
      dayByPerson.put(person.getId(), completed);
      allDays.add(completed);
    }
    allDays.add(policy.night().completedShiftDay(now, zone)); // never an empty IN (...)

    Map<String, List<IclockPunch>> byPerson = new LinkedHashMap<>();
    for (IclockPunch p : punches.findBySiteIdAndShiftDateInOrderByEffectiveAtAsc(siteId, allDays)) {
      if (p.getPersonId() != null
          && p.getShiftDate() != null
          && p.getShiftDate().equals(dayByPerson.get(p.getPersonId()))) {
        byPerson.computeIfAbsent(p.getPersonId(), k -> new ArrayList<>()).add(p);
      }
    }
    if (byPerson.isEmpty()) {
      return List.of();
    }
    Set<String> companyIds =
        roster.values().stream()
            .map(IclockPerson::getCompanyId)
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    Map<String, String> companyNames =
        companyIds.isEmpty()
            ? Map.of()
            : companies.findAllById(companyIds).stream()
                .collect(java.util.stream.Collectors.toMap(Company::getId, Company::getName));

    List<MissingOut> out = new ArrayList<>();
    for (Map.Entry<String, List<IclockPunch>> e : byPerson.entrySet()) {
      List<IclockMissingOut.PunchFacts> facts =
          e.getValue().stream()
              .map(p -> new IclockMissingOut.PunchFacts(p.getEffectiveAt(), p.getArea(), p.getDirection()))
              .toList();
      if (!IclockMissingOut.isMissingOut(facts)) {
        continue;
      }
      IclockPunch last = e.getValue().get(e.getValue().size() - 1);
      IclockPerson person = roster.get(e.getKey());
      String company =
          person == null
              ? null
              : person.getCompanyId() == null
                  ? person.getCompanyLabel()
                  : companyNames.getOrDefault(person.getCompanyId(), person.getCompanyLabel());
      out.add(new MissingOut(
          e.getKey(), person == null ? null : person.getName(), company,
          dayByPerson.get(e.getKey()),
          last.getEffectiveAt(), last.getDirection(), last.getArea()));
    }
    out.sort(java.util.Comparator.comparing(MissingOut::lastPunchAt).reversed());
    return out;
  }

  /**
   * The alert strip, derived from the same chips the columns are built from — so the strip and the
   * badge on a person's chip can never disagree.
   *
   * <p>Sorted by ELAPSED DESCENDING: a deliberate, documented exception to the recency standard. These
   * are ranked by how overdue they are, and the most overdue person is the one worth walking over to.
   * Newest-first would put the person who just stepped out at the top of an alert list, which inverts
   * the only thing the list is for.
   */
  private List<BreakAlert> alertsFrom(
      java.util.Collection<PersonChip> chips, ZoneId zone, IclockSitePolicyService.PolicyView policy) {
    Instant now = Instant.now();
    List<BreakAlert> out = new ArrayList<>();
    for (PersonChip c : chips) {
      IclockBreakAlert.Where where =
          IclockBreakAlert.evaluate(
              c.lastAt(), c.lastArea(), c.lastDirection(), now, zone,
              policy.profileFor(c.shiftProfile()),
              policy.breakAlertMin(), policy.breakAlertMaxMin());
      if (where != null) {
        out.add(new BreakAlert(
            c.personId(), c.name(), c.companyName(), where.name(), c.lastAt(),
            IclockBreakAlert.elapsedMinutes(c.lastAt(), now)));
      }
    }
    out.sort(java.util.Comparator.comparingLong(BreakAlert::elapsedMinutes).reversed()
        .thenComparing(a -> a.name() == null ? "￿" : a.name()));
    return out;
  }

  /**
   * Board across ONE building, or across ALL of them when {@code siteId} is null.
   *
   * <p>Scoping is a FILTER, not a second pipeline: the all-buildings form is the per-building form run
   * over every site and merged, with the same chips, the same presence rules and the same ordering.
   * Building it as a separate query would be a second place for the presence logic to drift.
   *
   * <p>The merged columns are re-sorted, because concatenating four already-sorted lists does not give
   * a sorted list — the freshest punch in the second building belongs above a stale one in the first.
   */
  @Transactional(readOnly = true)
  public Board boardAcross(String siteId) {
    if (siteId != null) {
      return board(siteId);
    }
    List<IclockSite> all = sites.findAll();
    if (all.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No buildings yet");
    }
    List<PersonChip> inOffice = new ArrayList<>();
    List<PersonChip> inCafeteria = new ArrayList<>();
    List<PersonChip> left = new ArrayList<>();
    List<PersonChip> notArrived = new ArrayList<>();
    List<BreakAlert> alerts = new ArrayList<>();
    List<MissingOut> missing = new ArrayList<>();
    LocalDate missingDay = IclockMissingOut.completedShiftDay(Instant.now(), ShiftConfig.ZONE);
    LocalDate shiftDate = IclockShiftDay.of(Instant.now(), ShiftConfig.ZONE);
    for (IclockSite site : all) {
      Board one = board(site.getId());
      inOffice.addAll(one.inOffice());
      inCafeteria.addAll(one.inCafeteria());
      left.addAll(one.left());
      notArrived.addAll(one.notArrived());
      alerts.addAll(one.exceedingBreak());
      missing.addAll(one.missingOut());
      missingDay = one.missingOutShiftDate();
      shiftDate = one.shiftDate();
    }
    inOffice.sort(NEWEST_FIRST);
    inCafeteria.sort(NEWEST_FIRST);
    left.sort(NEWEST_FIRST);
    notArrived.sort(BY_NAME);
    // Re-sorted for the same reason the columns are: concatenating per-building alert lists does not
    // give a list ordered by how overdue people are.
    alerts.sort(java.util.Comparator.comparingLong(BreakAlert::elapsedMinutes).reversed()
        .thenComparing(a -> a.name() == null ? "￿" : a.name()));
    missing.sort(java.util.Comparator.comparing(MissingOut::lastPunchAt).reversed());
    return new Board(null, shiftDate, Instant.now(), inOffice, inCafeteria, left, notArrived, alerts,
        missing, missingDay);
  }

  /**
   * Overview across one building or all of them.
   *
   * <p>Counts SUM and device health concatenates; the shift-day is shared because every building runs
   * the same shift today. {@code siteName} becomes "All buildings" so the console can label it without
   * knowing whether it asked for one or many.
   */
  @Transactional(readOnly = true)
  public Overview overviewAcross(String siteId) {
    if (siteId != null) {
      return overview(siteId);
    }
    List<IclockSite> all = sites.findAll();
    if (all.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No buildings yet");
    }
    long roster = 0, present = 0, arrived = 0, notArrived = 0, late = 0, punchesToday = 0;
    List<DeviceHealth> devicesOut = new ArrayList<>();
    LocalDate shiftDate = IclockShiftDay.of(Instant.now(), ShiftConfig.ZONE);
    for (IclockSite site : all) {
      Overview o = overview(site.getId());
      roster += o.rosterSize();
      present += o.presentNow();
      arrived += o.arrived();
      notArrived += o.notArrived();
      late += o.lateSoFar();
      punchesToday += o.punchesToday();
      devicesOut.addAll(o.devices());
      shiftDate = o.shiftDate();
    }
    return new Overview(null, "All buildings", shiftDate, roster, present, arrived, notArrived,
        late, punchesToday, devicesOut);
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
    // "Today" for this person means today ON THEIR SHIFT — a day-shift person opening the view at
    // 10:00 is mid-shift, and the night profile would show them the previous date's empty grid.
    LocalDate day = shiftDateOrNull == null || shiftDateOrNull.isBlank()
        ? policies.effective(person.getSiteId())
            .profileFor(person.getShiftProfile())
            .shiftDateOf(Instant.now(), zone)
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

  /**
   * Which shift-day each person is currently on, and the set of days that covers.
   *
   * <p>A building can run more than one shift, and the profiles disagree about what "today" is for the
   * ten hours between the day cut (01:30) and the night one (11:30). One date for the whole board would
   * silently drop whichever population sits on the other side of that disagreement — they would read as
   * NOT_ARRIVED all morning while standing at their desks.
   */
  private record ShiftDays(
      Map<String, LocalDate> byPerson, Set<LocalDate> all, LocalDate defaultDay) {}

  private ShiftDays shiftDaysFor(
      List<IclockPerson> roster, Instant now, ZoneId zone,
      IclockSitePolicyService.PolicyView policy) {
    Map<String, LocalDate> byPerson = new LinkedHashMap<>();
    Set<LocalDate> all = new java.util.LinkedHashSet<>();
    for (IclockPerson person : roster) {
      LocalDate day = policy.profileFor(person.getShiftProfile()).shiftDateOf(now, zone);
      byPerson.put(person.getId(), day);
      all.add(day);
    }
    // The header's day, and the fallback for anything with no person in hand. Always present, so an
    // empty roster still produces a legal `IN (...)` rather than a syntax error on an empty list.
    LocalDate defaultDay = policy.night().shiftDateOf(now, zone);
    all.add(defaultDay);
    return new ShiftDays(byPerson, all, defaultDay);
  }

  private Map<String, PersonChip> chipsFor(
      String siteId, String siteName, List<IclockPerson> roster, ShiftDays days, ZoneId zone,
      IclockSitePolicyService.PolicyView policy) {
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
    //
    // Fetched across every shift-day in play and then matched to the person's OWN day. Without that
    // second filter a night-shift person's punch from the still-open night would be picked up as a
    // day-shift person's "latest", and the board would show somebody present on a shift they do not
    // work.
    for (IclockPunch p : punches.findBySiteIdAndShiftDateInOrderByEffectiveAtDesc(siteId, days.all())) {
      if (p.getPersonId() != null
          && p.getShiftDate() != null
          && p.getShiftDate().equals(days.byPerson().get(p.getPersonId()))) {
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
      boolean alerting =
          last != null
              && IclockBreakAlert.evaluate(
                      last.getEffectiveAt(), last.getArea(), last.getDirection(),
                      Instant.now(), zone, policy.profileFor(person.getShiftProfile()),
                      policy.breakAlertMin(), policy.breakAlertMaxMin())
                  != null;
      out.put(person.getId(), new PersonChip(
          person.getId(), person.getName(), person.getPin(), companyName, person.getTeam(), siteName,
          person.getShiftProfile(),
          presence, last == null ? null : last.getEffectiveAt(),
          last == null ? null : last.getDirection(),
          last == null ? null : last.getArea(),
          alerting));
    }
    return out;
  }

  /**
   * People whose first gate IN was after THEIR OWN shift's late threshold. lateExemptMin is a P2 input.
   *
   * <p>The threshold is per person because the shift is: 19:15 for the night shift, 09:15 for the day
   * shift. A single threshold would mark every day-shift arrival as late by about ten hours, which is
   * the sort of number that discredits a report rather than being noticed as a bug.
   */
  private long lateCount(
      String siteId, List<IclockPerson> roster, ShiftDays days, ZoneId zone,
      IclockSitePolicyService.PolicyView policy) {
    Map<String, IclockPerson> byId = new LinkedHashMap<>();
    for (IclockPerson person : roster) {
      byId.put(person.getId(), person);
    }
    Map<String, Instant> firstIn = new LinkedHashMap<>();
    for (IclockPunch p : punches.findBySiteIdAndShiftDateInOrderByEffectiveAtDesc(siteId, days.all())) {
      if (p.getPersonId() != null
          && "IN".equals(p.getDirection())
          && "GATE".equals(p.getArea())
          && p.getShiftDate() != null
          && p.getShiftDate().equals(days.byPerson().get(p.getPersonId()))) {
        firstIn.put(p.getPersonId(), p.getEffectiveAt()); // newest-first, so the last write is earliest
      }
    }
    long late = 0;
    for (Map.Entry<String, Instant> e : firstIn.entrySet()) {
      IclockPerson person = byId.get(e.getKey());
      if (person == null) {
        continue; // punched at this site but not on its active roster — not a lateness question
      }
      IclockShiftProfile profile = policy.profileFor(person.getShiftProfile());
      LocalDate day = days.byPerson().get(e.getKey());
      if (e.getValue().isAfter(profile.lateThreshold(day, zone))) {
        late++;
      }
    }
    return late;
  }

  private static ZoneId zoneOf(String tz) {
    try {
      return ZoneId.of(tz);
    } catch (Exception e) {
      return ShiftConfig.ZONE;
    }
  }
}
