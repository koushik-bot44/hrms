package com.ihrms.iclock;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A shift, and everything derived from it — as a pure value.
 *
 * <p>Until now the shift was a set of global constants, which was true while every person worked
 * 19:00&ndash;04:00 and became a defect the moment they did not. A day-shift person under the night
 * profile has their 09:00 IN and 18:00 OUT filed on DIFFERENT shift days, so their sessions never pair
 * and every day reads as an unpaired IN plus an orphan OUT. That is the same failure the 04:00 cut
 * produced for night staff, arriving from the other end of the clock.
 *
 * <p><b>The cut is derived, never written down.</b> It sits at the midpoint of the non-working window —
 * the point furthest from any real punch — and the arithmetic is the same for both directions because
 * the gap is computed modulo a day:
 *
 * <pre>
 *   NIGHT 19:00 -&gt; 04:00 : gap = (19:00 - 04:00) mod 24h = 15h, cut = 04:00 + 7h30 = 11:30
 *   DAY   09:00 -&gt; 18:00 : gap = (09:00 - 18:00) mod 24h = 15h, cut = 18:00 + 7h30 = 01:30
 * </pre>
 *
 * <p>That is why a day person's 18:00 exit and 09:00 arrival land on the same date, and why their
 * overtime past midnight still belongs to the day it started.
 */
record IclockShiftProfile(String name, LocalTime start, LocalTime end, int lateGraceMin) {

  /** The default everyone stays on until an operator says otherwise. Current behaviour, unchanged. */
  static final IclockShiftProfile NIGHT =
      new IclockShiftProfile("NIGHT", LocalTime.of(19, 0), LocalTime.of(4, 0), 15);

  /** Applied per person, only from a confirmed list — never inferred from punch times. */
  static final IclockShiftProfile DAY =
      new IclockShiftProfile("DAY", LocalTime.of(9, 0), LocalTime.of(18, 0), 15);

  static IclockShiftProfile byName(String name) {
    return "DAY".equals(name) ? DAY : NIGHT;
  }

  /** True when the shift runs past midnight. Both profiles' maths flow from this. */
  boolean overnight() {
    return end.isBefore(start) || end.equals(start);
  }

  /**
   * How long the shift lasts. Wrap-aware, so an overnight shift is 9 hours rather than negative 15.
   */
  Duration length() {
    long minutes = Math.floorMod(
        Duration.between(start, end).toMinutes(), Duration.ofDays(1).toMinutes());
    return Duration.ofMinutes(minutes == 0 ? Duration.ofDays(1).toMinutes() : minutes);
  }

  /**
   * The shift-day cutoff: the midpoint of the gap BETWEEN shifts.
   *
   * <p>Modulo a day in both directions, which is what lets one formula serve a night shift (cut 11:30,
   * in the middle of the morning) and a day shift (cut 01:30, in the middle of the night) without a
   * branch. A branch is what would eventually get one of them wrong.
   */
  LocalTime dayCut() {
    long gapMinutes = Math.floorMod(
        Duration.between(end, start).toMinutes(), Duration.ofDays(1).toMinutes());
    return end.plusMinutes(gapMinutes / 2);
  }

  /**
   * The shift-day an instant belongs to FOR THIS PROFILE.
   *
   * <p>Before the cut belongs to the previous day, which is what keeps a shift's tail with the day it
   * started — the overnight tail for a night shift, and post-midnight overtime for a day shift.
   */
  LocalDate shiftDateOf(Instant instant, ZoneId zone) {
    var local = instant.atZone(zone);
    return local.toLocalTime().isBefore(dayCut())
        ? local.toLocalDate().minusDays(1)
        : local.toLocalDate();
  }

  /** The instant this shift-day's shift opens. */
  Instant startOf(LocalDate shiftDate, ZoneId zone) {
    return shiftDate.atTime(start).atZone(zone).toInstant();
  }

  /**
   * The instant this shift-day's shift closes — the NEXT day for an overnight shift.
   *
   * <p>Getting this wrong is what makes a "has the shift ended" test answer for the wrong day, which is
   * how a completed-shift-day rule silently reports yesterday all evening.
   */
  Instant endOf(LocalDate shiftDate, ZoneId zone) {
    LocalDate endDate = overnight() ? shiftDate.plusDays(1) : shiftDate;
    return endDate.atTime(end).atZone(zone).toInstant();
  }

  /** The instant after which a first arrival counts as late. */
  Instant lateThreshold(LocalDate shiftDate, ZoneId zone) {
    return startOf(shiftDate, zone).plus(Duration.ofMinutes(lateGraceMin));
  }

  /**
   * Whether {@code now} falls inside this shift's working hours.
   *
   * <p>Two ranges when the shift wraps midnight, one when it does not. Collapsing that into a single
   * comparison is the mistake that makes a night shift's alerts silent for its entire post-midnight
   * half — and would make a day shift's alerts fire all night.
   */
  boolean withinShift(Instant now, ZoneId zone) {
    LocalTime t = now.atZone(zone).toLocalTime();
    return overnight()
        ? !t.isBefore(start) || t.isBefore(end)
        : !t.isBefore(start) && t.isBefore(end);
  }

  /**
   * The most recently COMPLETED shift day for this profile.
   *
   * <p>Expressed as "has this shift ended yet" rather than as time-of-day branches, because the
   * branches are what get one case wrong.
   */
  LocalDate completedShiftDay(Instant now, ZoneId zone) {
    LocalDate current = shiftDateOf(now, zone);
    return now.isBefore(endOf(current, zone)) ? current.minusDays(1) : current;
  }
}
