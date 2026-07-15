package com.ihrms.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Fixed overnight shift constants + the shift-day / late rules (§8a v2). Centralized here so the shift is
 * easy to change later. Timezone Asia/Kolkata; shift 19:00 → 04:00 (next day); LATE after 19:20.
 */
public final class ShiftConfig {

  private ShiftConfig() {}

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  public static final LocalTime SHIFT_START = LocalTime.of(19, 0);
  public static final LocalTime LATE_AFTER = LocalTime.of(19, 20);
  /** Next-day shift end — also the shift-day cutoff (before this, a clock-in belongs to the previous day). */
  public static final LocalTime SHIFT_END = LocalTime.of(4, 0);

  /**
   * The shift-day a clock-in belongs to: its IST date, minus one day when the IST time is before the 04:00
   * shift-end cutoff (so the 19:00→04:00 tail stays with the day the shift started).
   */
  public static LocalDate shiftDateOf(Instant clockIn) {
    var ist = clockIn.atZone(ZONE);
    return ist.toLocalTime().isBefore(SHIFT_END) ? ist.toLocalDate().minusDays(1) : ist.toLocalDate();
  }

  /** The LATE threshold instant for a shift-day: 19:20 IST on that date. */
  public static Instant lateThreshold(LocalDate shiftDate) {
    return shiftDate.atTime(LATE_AFTER).atZone(ZONE).toInstant();
  }

  /** Whole minutes a clock-in is past the shift-day's 19:20 threshold (0 if at/before it). */
  public static long lateMinutes(Instant clockIn, LocalDate shiftDate) {
    Instant threshold = lateThreshold(shiftDate);
    return clockIn.isAfter(threshold) ? Math.max(0, Duration.between(threshold, clockIn).toMinutes()) : 0;
  }

  /** Whether a clock-in that is the FIRST of its shift-day counts as late (after the 19:20 threshold). */
  public static boolean isLate(Instant clockIn, LocalDate shiftDate) {
    return clockIn.isAfter(lateThreshold(shiftDate));
  }
}
