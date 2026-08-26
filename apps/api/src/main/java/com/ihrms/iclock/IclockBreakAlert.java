package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * "Exceeding break" membership, as a pure function.
 *
 * <p>An overlay lens, not a fifth presence state. Someone in this alert is still IN_CAFETERIA or LEFT —
 * the columns stay canonical, and the alert simply says a particular absence has run long. Making it a
 * presence state would mean a person leaving the building could no longer be found where the operator
 * expects them.
 *
 * <p>Pure and Spring-free for the same reason the grouping rule is: membership is the whole behaviour,
 * so it should be assertable without a database, a board, or a clock that only moves forwards.
 */
final class IclockBreakAlert {

  private IclockBreakAlert() {}

  /** Where the person went. Both are "away"; the distinction is what the operator does about it. */
  enum Where {
    /** Last punch was a GATE OUT — they left the building. */
    OUTSIDE,
    /** Last punch was a CAFETERIA IN — under the confirmed inversion, that STARTS a break. */
    CAFETERIA
  }

  /**
   * Whether this person's current absence counts as an over-long break, and where they went.
   *
   * <p>Returns null when they do not belong in the alert, so a caller cannot accidentally treat
   * "not alerting" as a where-value.
   *
   * @param lastAt their most recent punch, or null if they have not arrived this shift day
   * @param lastArea GATE or CAFETERIA
   * @param lastDirection IN or OUT
   * @param now evaluated, never {@code Instant.now()} inside, so tests can place the clock
   * @param minMinutes below this, an absence is just a break
   * @param maxMinutes above this, a continuous absence is presumed a departure and the alert retires
   */
  static Where evaluate(
      Instant lastAt,
      String lastArea,
      String lastDirection,
      Instant now,
      ZoneId zone,
      int minMinutes,
      int maxMinutes) {
    // Never arrived — there is no absence to measure.
    if (lastAt == null) {
      return null;
    }
    // No alerts outside working hours: at 11:00 everyone is "away" and none of it means anything.
    if (!withinShift(now, zone)) {
      return null;
    }

    Where where = awayKind(lastArea, lastDirection);
    if (where == null) {
      return null; // their latest punch put them AT work, so nothing is running
    }

    long minutes = Duration.between(lastAt, now).toMinutes();
    if (minutes <= minMinutes) {
      return null; // still within a normal break
    }
    // THE CAP, and it is a judgement rather than a measurement. Without it, everyone who goes home
    // early sits in the alert until 04:00 — the strip fills with people who are simply finished, and
    // an alert nobody can clear is one the operator stops reading. Past the cap the absence is
    // presumed a departure and the person just stays in "Left".
    if (minutes >= maxMinutes) {
      return null;
    }
    return where;
  }

  /**
   * Which kind of away-punch this is, or null if the person is at work.
   *
   * <p>CAFETERIA IN starts a break — "IN for cafeteria is OUT for work", the confirmed inversion. GATE
   * OUT is the plain case and is the only one that fires today, since no cafeteria terminal is claimed;
   * the cafeteria half activates on its own the moment one is.
   */
  static Where awayKind(String area, String direction) {
    if ("CAFETERIA".equals(area) && "IN".equals(direction)) {
      return Where.CAFETERIA;
    }
    if ("GATE".equals(area) && "OUT".equals(direction)) {
      return Where.OUTSIDE;
    }
    return null;
  }

  /**
   * Whether {@code now} falls inside the 19:00 to 04:00 shift.
   *
   * <p>Wraps midnight, so it is two ranges rather than one comparison — the mistake that would make the
   * alert silent for the entire post-midnight half of every shift.
   */
  static boolean withinShift(Instant now, ZoneId zone) {
    LocalTime t = now.atZone(zone).toLocalTime();
    LocalTime start = ShiftConfig.SHIFT_START;
    LocalTime end = ShiftConfig.SHIFT_END;
    return start.isBefore(end)
        ? !t.isBefore(start) && t.isBefore(end)
        : !t.isBefore(start) || t.isBefore(end);
  }

  /** Whole minutes a person has been away. Never negative, so a clock skew cannot read as "-3m". */
  static long elapsedMinutes(Instant lastAt, Instant now) {
    return Math.max(0, Duration.between(lastAt, now).toMinutes());
  }
}
