package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Shift-day attribution for the DEFAULT (night) profile, in the site's timezone.
 *
 * <p><b>Use this only where no person is in hand.</b> Since V47 the shift-day cut is a property of the
 * person's shift, not of the system: a day-shift person cuts at 01:30 and a night-shift person at
 * 11:30. Anything that knows whose punch it is must go through
 * {@link IclockShiftProfile#shiftDateOf(Instant, ZoneId)} on THEIR profile instead — the promotion
 * path, the board, Missing OUT and the late count all do. What is left here is the site-wide default:
 * totals, and the day the console opens on.
 *
 * <p>Delegates to {@link IclockShiftProfile#NIGHT} rather than repeating the arithmetic, so there is
 * exactly one derivation of the cut in the codebase. This class previously carried its own copy of it,
 * which is precisely the arrangement that let a constant drift out of agreement with the shift it
 * described.
 */
final class IclockShiftDay {

  private IclockShiftDay() {}

  /**
   * The shift-day an instant belongs to under the default night shift: its local date in {@code zone},
   * minus one day when the local time falls before the 11:30 cut, so the overnight tail stays with the
   * day the shift started.
   */
  static LocalDate of(Instant instant, ZoneId zone) {
    return IclockShiftProfile.NIGHT.shiftDateOf(instant, zone);
  }

  /**
   * The default profile itself, for callers that need more than the date — the shift's end, its late
   * threshold, or whether the clock is currently inside it.
   *
   * <p>{@link ShiftConfig} remains the manual clock-in path's own copy of the night shift, and
   * {@code IclockShiftProfileTest} asserts the two agree so a change to one cannot silently diverge.
   */
  static IclockShiftProfile defaultProfile() {
    return IclockShiftProfile.NIGHT;
  }
}
