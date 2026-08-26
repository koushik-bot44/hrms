package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Shift-day attribution for an effective punch, in the SITE's timezone.
 *
 * <p>This mirrors {@link ShiftConfig#shiftDateOf(Instant)} exactly — the 19:00→04:00 overnight shift,
 * cut at 04:00 — but takes the zone as a parameter instead of using {@link ShiftConfig#ZONE}, which is
 * a hardcoded {@code Asia/Kolkata} constant.
 *
 * <p><b>This is a deliberate, contained fork and it must not drift.</b> {@code ShiftConfig} owns
 * shift-day attribution for the manual clock-in path; the site timezone owns it for the device path.
 * For {@code Asia/Kolkata} the two produce identical results, and {@code IclockShiftDayTest} asserts
 * that agreement so a change to one is caught rather than silently diverging. The admin API refuses to
 * create a site in any other timezone until {@code ShiftConfig} itself is parameterised — at which
 * point this class should collapse back into it.
 */
final class IclockShiftDay {

  private IclockShiftDay() {}

  /**
   * The shift-day an instant belongs to: its local date in {@code zone}, minus one day when the local
   * time falls before {@link ShiftConfig#DAY_CUT}, so the overnight tail stays with the day the shift
   * started.
   *
   * <p>The cut is the MIDPOINT of the non-working window (11:30 IST), not the shift end. It used to be
   * the shift end, which filed the closing OUT of every full shift on the following day.
   */
  static LocalDate of(Instant instant, ZoneId zone) {
    var local = instant.atZone(zone);
    return local.toLocalTime().isBefore(ShiftConfig.DAY_CUT)
        ? local.toLocalDate().minusDays(1)
        : local.toLocalDate();
  }
}
