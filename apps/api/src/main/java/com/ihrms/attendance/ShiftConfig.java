package com.ihrms.attendance;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Fixed overnight shift constants + the shift-day / late rules (§8a v2). Centralized here so the shift is
 * easy to change later. Timezone Asia/Kolkata; shift 19:00 → 04:00 (next day); LATE after 19:15.
 */
public final class ShiftConfig {

  private ShiftConfig() {}

  public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
  public static final LocalTime SHIFT_START = LocalTime.of(19, 0);
  public static final LocalTime LATE_AFTER = LocalTime.of(19, 15);
  /** Next-day shift end. NOT the shift-day cutoff — see {@link #DAY_CUT}. */
  public static final LocalTime SHIFT_END = LocalTime.of(4, 0);

  /**
   * The shift-day cutoff: the MIDPOINT of the non-working window, 11:30 IST.
   *
   * <p><b>This used to be {@link #SHIFT_END}, and that was a live defect.</b> The cut sat on the shift's
   * own closing boundary, so a punch at exactly 04:00 — the moment the shift ends — was filed on the
   * NEXT shift-day. Anyone tapping out at or after 04:00, which is the normal case for someone who
   * works a full shift, left behind an unpaired IN and a null lastOut on the day they actually worked,
   * and an orphan OUT on the following day. Every derived number (worked minutes, breaks, lateness,
   * LOP) is computed downstream of the shift-day, so the error propagated into all of them.
   *
   * <p>Placing the cut at the midpoint of the gap between shifts puts the maximum distance between it
   * and any real punch: a full 7.5 hours either side of 11:30 before anyone plausibly taps. Derived
   * rather than written as a literal so that moving the shift moves the cut with it — the constant that
   * caused this bug was a literal that stopped agreeing with the shift around it.
   *
   * <p>Assumes an OVERNIGHT shift ({@code SHIFT_END} before {@code SHIFT_START} on the clock). A
   * same-day shift would need the window computed the other way round.
   */
  public static final LocalTime DAY_CUT =
      SHIFT_END.plusMinutes(Duration.between(SHIFT_END, SHIFT_START).toMinutes() / 2);

  /**
   * The shift-day a clock-in belongs to: its IST date, minus one day when the IST time is before the
   * {@link #DAY_CUT} (so the whole 19:00→04:00 shift, tail included, stays with the day it started).
   *
   * <p>The edges: {@code isBefore} the cut means the previous day, so 04:00:00 exactly — and anything
   * up to 11:29:59 — files on the CLOSING shift, which is the whole point of the change.
   */
  public static LocalDate shiftDateOf(Instant clockIn) {
    var ist = clockIn.atZone(ZONE);
    return ist.toLocalTime().isBefore(DAY_CUT) ? ist.toLocalDate().minusDays(1) : ist.toLocalDate();
  }

  /** The LATE threshold instant for a shift-day: 19:15 IST on that date. */
  public static Instant lateThreshold(LocalDate shiftDate) {
    return shiftDate.atTime(LATE_AFTER).atZone(ZONE).toInstant();
  }

  /** Whole minutes a clock-in is past the shift-day's 19:15 threshold (0 if at/before it). */
  public static long lateMinutes(Instant clockIn, LocalDate shiftDate) {
    Instant threshold = lateThreshold(shiftDate);
    return clockIn.isAfter(threshold) ? Math.max(0, Duration.between(threshold, clockIn).toMinutes()) : 0;
  }

  /** Whether a clock-in that is the FIRST of its shift-day counts as late (after the 19:15 threshold). */
  public static boolean isLate(Instant clockIn, LocalDate shiftDate) {
    return clockIn.isAfter(lateThreshold(shiftDate));
  }
}
