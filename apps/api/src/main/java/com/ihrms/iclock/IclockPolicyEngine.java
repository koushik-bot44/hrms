package com.ihrms.iclock;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The attendance rulebook, ported from the legacy analyzer — as a pure function.
 *
 * <p>This is the reason the project exists. The analyzer is a single-page app the operator runs by
 * hand against a spreadsheet export; everything it computes is here instead, derived from punches the
 * pipeline already has, so the numbers stop depending on who ran which macro on which file.
 *
 * <p><b>Deliberate divergences from the legacy, all in the direction of not inventing data:</b>
 *
 * <ul>
 *   <li><b>An unclosed session contributes nothing.</b> The analyzer estimated the missing OUT as the
 *       midpoint between two INs and counted the result as worked time. That is a guess presented as
 *       a measurement, and it feeds payroll. Here the day is flagged {@link DayFacts#hasMissingPunch}
 *       and the open stretch is worth zero until somebody regularises it.
 *   <li><b>Breaks come from {@link IclockDaySessions}</b>, which knows the cafeteria readers sit
 *       outside the gate line. The analyzer paired any OUT with the next IN, so on this fleet it would
 *       read the walk to the canteen as the whole break and the walk back as a new working day.
 *   <li><b>Weekly-off is derived from the calendar, not from a blank row.</b> The analyzer inferred it
 *       from an absent status plus no punches, which quietly turned a genuine unexplained absence on a
 *       Saturday into a day off.
 * </ul>
 *
 * <p>Everything else is the legacy arithmetic, kept deliberately identical so a month computed here
 * can be reconciled against a month the analyzer produced.
 */
final class IclockPolicyEngine {

  private IclockPolicyEngine() {}

  /** What a day turned out to be. */
  enum Status {
    PRESENT,
    ABSENT,
    WEEKLY_OFF,
    HOLIDAY
  }

  /**
   * The knobs. Defaults match the analyzer's own default settings, which is what the operator has
   * been running against for months.
   */
  record Policy(
      /** Hours in a full shift. Work is CAPPED at this — the analyzer's {@code capWork}. */
      double shiftHours,
      /** Paid break per present day, in minutes. Legacy default 60 ("1hr/day"). */
      int allowedBreakMin,
      /** Gaps shorter than this are not breaks at all. Legacy default 5. */
      int minBreakMin,
      /**
       * How many late arrivals are forgiven before each further one costs a day's pay.
       *
       * <p>Legacy: {@code lop = max(0, lateDays - 3)}, described to employees as "only the first
       * three late logins are permitted; from the fourth occurrence onwards each is treated as one
       * day of Leave Without Pay".
       */
      int lopAfterLateDays) {

    static Policy defaults() {
      return new Policy(9, 60, 5, 3);
    }

    /** Expected working minutes on a present day: the shift, less the paid break. */
    long expectedWorkMinPerDay() {
      return Math.round(shiftHours * 60) - allowedBreakMin;
    }

    /** Total minutes on site for a present day, break included. */
    long officeMinPerDay() {
      return Math.round(shiftHours * 60);
    }
  }

  /** One person's punches for one shift day, already attributed. */
  record DayInput(
      LocalDate shiftDate,
      List<IclockDaySessions.Punch> punches,
      /** Their shift, which decides the late threshold. */
      IclockShiftProfile profile,
      /**
       * Minutes of lateness forgiven for this person before they count as late at all.
       *
       * <p>The analyzer's per-employee exemption list. When set, it REPLACES the threshold test:
       * lateness is measured from shift start and reduced by the exemption, and any remainder is
       * late. When zero, the plain threshold applies.
       */
      int lateExemptMin,
      /** A company holiday or an approved group exemption — no lateness is assessed. */
      boolean dayExempted) {}

  /** What one day was worth. */
  record DayFacts(
      LocalDate shiftDate,
      Status status,
      Instant firstIn,
      Instant lastOut,
      /** Worked minutes AFTER the shift-hours cap. */
      long workedMin,
      /** Before the cap, so an over-long day is visible rather than silently trimmed. */
      long workedRawMin,
      long breakMin,
      long cafeteriaMin,
      boolean late,
      long lateMin,
      /** True when a session never closed. Its time is NOT counted. */
      boolean hasMissingPunch,
      boolean dayExempted) {

    /** True when the cap actually bit — the operator sees "capped from" in the analyzer's day row. */
    boolean capped() {
      return workedRawMin > workedMin;
    }
  }

  /**
   * Derives one day.
   *
   * @param zone the site timezone; lateness is a wall-clock question
   */
  static DayFacts day(DayInput in, Policy policy, ZoneId zone) {
    List<IclockDaySessions.Segment> segments = IclockDaySessions.segment(in.punches());

    long workedRaw = IclockDaySessions.workedMinutes(segments);
    long capped = Math.min(workedRaw, policy.officeMinPerDay());

    // Gaps below the floor are not breaks. Someone stepping out for two minutes has not taken one,
    // and counting it would make every day look over-broken.
    long breakMin = 0;
    long cafeteriaMin = 0;
    for (IclockDaySessions.Segment s : segments) {
      if (!s.cafeteria() || s.from() == null || s.to() == null) {
        continue;
      }
      long minutes = Duration.between(s.from(), s.to()).toMinutes();
      if (minutes >= policy.minBreakMin()) {
        breakMin += minutes;
        cafeteriaMin += minutes;
      }
    }

    boolean missing = segments.stream().anyMatch(IclockDaySessions.Segment::estimated);
    Instant firstIn = IclockDaySessions.firstArrival(in.punches());
    Instant lastOut = IclockDaySessions.lastDeparture(in.punches());

    Status status = statusOf(in, firstIn, zone);
    if (status != Status.PRESENT) {
      return new DayFacts(in.shiftDate(), status, firstIn, lastOut, 0, 0, 0, 0,
          false, 0, false, in.dayExempted());
    }

    // Lateness is not assessed on a day nobody was expected to work.
    long lateMin = 0;
    boolean late = false;
    if (!in.dayExempted()) {
      Instant shiftStart = in.profile().startOf(in.shiftDate(), zone);
      long rawLate = firstIn == null || !firstIn.isAfter(shiftStart)
          ? 0
          : Duration.between(shiftStart, firstIn).toMinutes();
      if (in.lateExemptMin() > 0) {
        // The exemption REPLACES the grace threshold rather than stacking with it — that is how the
        // analyzer behaves, and stacking would silently forgive the grace twice.
        lateMin = Math.max(0, rawLate - in.lateExemptMin());
        late = lateMin > 0;
      } else {
        Instant threshold = in.profile().lateThreshold(in.shiftDate(), zone);
        late = firstIn != null && firstIn.isAfter(threshold);
        lateMin = late ? rawLate : 0;
      }
    }

    return new DayFacts(in.shiftDate(), Status.PRESENT, firstIn, lastOut,
        capped, workedRaw, breakMin, cafeteriaMin, late, lateMin, missing, in.dayExempted());
  }

  /**
   * What kind of day this was.
   *
   * <p>ANY punch makes it present — the analyzer's "single punch = Present override". Somebody who
   * tapped in once and never tapped out was at work; refusing to call that present would erase them.
   */
  private static Status statusOf(DayInput in, Instant firstIn, ZoneId zone) {
    boolean anyPunch = in.punches() != null && !in.punches().isEmpty();
    if (anyPunch) {
      return Status.PRESENT;
    }
    if (in.dayExempted()) {
      return Status.HOLIDAY;
    }
    DayOfWeek dow = in.shiftDate().getDayOfWeek();
    if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
      return Status.WEEKLY_OFF;
    }
    return Status.ABSENT;
  }

  /** A month, or any span, rolled up for one person. */
  record PeriodFacts(
      int totalDays,
      int presentDays,
      int absentDays,
      int weeklyOffDays,
      int holidayDays,
      /** Days actually owed: total less weekly-offs and holidays. */
      int workingDays,
      long totalWorkedMin,
      long totalBreakMin,
      long totalCafeteriaMin,
      long totalLateMin,
      int lateDays,
      long avgLateMin,
      int daysWithMissingPunch,
      long expectedWorkMin,
      long allowedBreakMin,
      long excessBreakMin,
      long officeMin,
      long productiveMin,
      long unproductiveMin,
      int productivePct,
      int workedPct,
      /** Days of Leave Without Pay earned by repeat lateness. */
      int lopDays,
      List<LocalDate> lateDates) {}

  /**
   * Rolls a set of days into the figures the report, the export and the warning mail all quote.
   *
   * <p>Percentages are computed here rather than in three renderers, because three renderers is three
   * chances to divide by a different denominator and produce three numbers for one fact.
   */
  static PeriodFacts period(Collection<DayFacts> days, Policy policy) {
    int present = 0, absent = 0, weeklyOff = 0, holiday = 0, lateDays = 0, missing = 0;
    long worked = 0, breaks = 0, cafeteria = 0, lateMin = 0;
    List<LocalDate> lateDates = new ArrayList<>();

    for (DayFacts d : days) {
      switch (d.status()) {
        case PRESENT -> {
          present++;
          worked += d.workedMin();
          breaks += d.breakMin();
          cafeteria += d.cafeteriaMin();
          if (d.late()) {
            lateDays++;
            lateMin += d.lateMin();
            lateDates.add(d.shiftDate());
          }
          if (d.hasMissingPunch()) {
            missing++;
          }
        }
        case ABSENT -> absent++;
        case WEEKLY_OFF -> weeklyOff++;
        case HOLIDAY -> holiday++;
      }
    }

    int total = days.size();
    long expected = (long) present * policy.expectedWorkMinPerDay();
    long allowedBreak = (long) present * policy.allowedBreakMin();
    long office = (long) present * policy.officeMinPerDay();
    long unproductive = breaks + lateMin;
    long productive = Math.max(0, office - unproductive);

    lateDates.sort(LocalDate::compareTo);

    return new PeriodFacts(
        total,
        present,
        absent,
        weeklyOff,
        holiday,
        total - weeklyOff - holiday,
        worked,
        breaks,
        cafeteria,
        lateMin,
        lateDays,
        lateDays > 0 ? Math.round((double) lateMin / lateDays) : 0,
        missing,
        expected,
        allowedBreak,
        Math.max(0, breaks - allowedBreak),
        office,
        productive,
        unproductive,
        office > 0 ? (int) Math.round(productive * 100.0 / office) : 0,
        expected > 0 ? (int) Math.round(worked * 100.0 / expected) : 0,
        lopDays(lateDays, policy),
        List.copyOf(lateDates));
  }

  /**
   * Days of pay lost to repeat lateness.
   *
   * <p>The one number in this file an employee will argue about, so it is stated plainly: the first
   * {@code lopAfterLateDays} late arrivals cost nothing, and every one after that costs a day. It is
   * a count of DAYS late, never a function of how late — arriving four minutes late four times costs
   * a day, and that is the policy as written, not a rounding artefact.
   */
  static int lopDays(int lateDays, Policy policy) {
    return Math.max(0, lateDays - policy.lopAfterLateDays());
  }

  /** Excluded people never reach a report, an export or a mail. Checked in one place, on purpose. */
  static <T> List<T> reportable(Collection<T> all, Set<T> excluded) {
    return all.stream().filter(x -> !excluded.contains(x)).toList();
  }
}
