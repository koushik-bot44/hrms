package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Shift-day attribution for device punches.
 *
 * <p>The org's real convention, confirmed against the production analyser: the default shift runs
 * 19:00 → 04:00 IST, so 2 AM belongs to the shift that started at 19:00 the previous day.
 *
 * <p>{@link IclockShiftDay} is a deliberate fork of {@link ShiftConfig#shiftDateOf} that takes the
 * site's zone as a parameter. These tests pin the agreement between the two for {@code Asia/Kolkata},
 * so a change to either is caught rather than silently diverging.
 */
class IclockShiftDayTest {

  private static final ZoneId IST = ShiftConfig.ZONE;

  private static Instant ist(String localDateTime) {
    return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
  }

  @ParameterizedTest
  @CsvSource({
    // punch (IST)          expected shift-day
    "2026-08-25T17:45:00,   2026-08-25", // PINNED: early arrival, before the 19:00 start
    "2026-08-25T19:00:00,   2026-08-25", // shift start
    "2026-08-25T19:15:00,   2026-08-25", // the late threshold
    "2026-08-25T23:59:59,   2026-08-25",
    "2026-08-26T00:00:00,   2026-08-25", // past midnight, same shift
    "2026-08-26T02:00:00,   2026-08-25", // the 2 AM case from the brief
    "2026-08-26T03:59:00,   2026-08-25", // PINNED: IN just before shift end
    "2026-08-26T03:59:59,   2026-08-25", // last moment before the old (broken) cut
    "2026-08-26T04:00:00,   2026-08-25", // PINNED: the closing OUT — files on the shift it CLOSES
    "2026-08-26T05:47:00,   2026-08-25", // PINNED: a late leaver, still the shift they worked
    "2026-08-26T11:29:59,   2026-08-25", // last moment before the cut
    "2026-08-26T11:30:00,   2026-08-26", // the cut: a new shift-day begins
    "2026-08-26T17:45:00,   2026-08-26", // arriving for the next shift
  })
  void attributesTheOvernightTailToTheDayTheShiftStarted(String punch, String expected) {
    assertThat(IclockShiftDay.of(ist(punch), IST)).isEqualTo(LocalDate.parse(expected));
  }

  @ParameterizedTest
  @CsvSource({
    "2026-08-25T17:45:00", "2026-08-25T19:00:00", "2026-08-26T00:00:00", "2026-08-26T02:00:00",
    "2026-08-26T03:59:00", "2026-08-26T04:00:00", "2026-08-26T05:47:00",
    "2026-08-26T11:29:59", "2026-08-26T11:30:00", "2026-08-26T12:30:00",
  })
  void agreesWithShiftConfigForIst(String punch) {
    // The fork must not drift. If ShiftConfig's rule changes, this fails loudly.
    Instant at = ist(punch);
    assertThat(IclockShiftDay.of(at, IST)).isEqualTo(ShiftConfig.shiftDateOf(at));
  }

  @Test
  void theClosingOutOfAFullShiftFilesOnTheShiftItCloses() {
    // THE REGRESSION THIS FIX EXISTS FOR. The cut used to be 04:00 — the shift's own closing boundary —
    // so tapping out at or after 04:00 filed the closing OUT on the NEXT shift-day, leaving an unpaired
    // IN and a null lastOut on the day actually worked. That is the normal case for a full shift, not
    // an edge case, and every derived number sits downstream of it.
    assertThat(IclockShiftDay.of(ist("2026-08-26T04:00:00"), IST))
        .as("04:00:00 exactly — the closing OUT")
        .isEqualTo(LocalDate.parse("2026-08-25"));
    assertThat(IclockShiftDay.of(ist("2026-08-26T05:47:00"), IST))
        .as("05:47 — a late leaver, still the shift they worked")
        .isEqualTo(LocalDate.parse("2026-08-25"));
  }

  @Test
  void theCutIsExclusiveAtTheMidpointOfTheNonWorkingWindow() {
    // 11:30 starts the new shift-day; one second before still belongs to the old one.
    assertThat(IclockShiftDay.of(ist("2026-08-26T11:30:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
    assertThat(IclockShiftDay.of(ist("2026-08-26T11:29:59"), IST))
        .isEqualTo(LocalDate.parse("2026-08-25"));
  }

  @Test
  void theCutIsDerivedFromTheShiftRatherThanWrittenDown() {
    // The bug was a literal that stopped agreeing with the shift around it. The cut is now the midpoint
    // of the gap between shifts, so moving the shift moves the cut — and it sits the maximum possible
    // distance from any real punch: 7.5 hours either side.
    assertThat(ShiftConfig.DAY_CUT).isEqualTo(java.time.LocalTime.of(11, 30));
    assertThat(java.time.Duration.between(ShiftConfig.SHIFT_END, ShiftConfig.DAY_CUT))
        .isEqualTo(java.time.Duration.between(ShiftConfig.DAY_CUT, ShiftConfig.SHIFT_START));
  }
}
