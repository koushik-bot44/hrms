package com.ihrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * The shift-day attribution + late rule (§8a v2), unit-tested with fixed IST instants (no DB / no clock to
 * mock). Shift 19:00→04:00; a clock-in belongs to the day the shift started (IST date minus one day when
 * the IST time is before 04:00). LATE is a clock-in after 19:20 IST.
 */
class ShiftConfigTest {

  // A reference date; the weekday is irrelevant to shift-day attribution.
  private static final int Y = 2026, MO = 3, D1 = 16, D2 = 17;

  private static Instant ist(int day, int hour, int minute) {
    return LocalDateTime.of(Y, MO, day, hour, minute).atZone(ShiftConfig.ZONE).toInstant();
  }

  @Test
  void shiftDayAttribution() {
    LocalDate day1 = LocalDate.of(Y, MO, D1);
    LocalDate day2 = LocalDate.of(Y, MO, D2);

    assertThat(ShiftConfig.shiftDateOf(ist(D1, 19, 0))).isEqualTo(day1); // shift start
    assertThat(ShiftConfig.shiftDateOf(ist(D1, 18, 30))).isEqualTo(day1); // early clock-in
    assertThat(ShiftConfig.shiftDateOf(ist(D2, 2, 0))).isEqualTo(day1); // tail of day1's shift
    assertThat(ShiftConfig.shiftDateOf(ist(D2, 3, 59))).isEqualTo(day1); // just before the 04:00 cutoff
    assertThat(ShiftConfig.shiftDateOf(ist(D2, 4, 0))).isEqualTo(day2); // at the cutoff -> next day
    assertThat(ShiftConfig.shiftDateOf(ist(D2, 5, 0))).isEqualTo(day2); // after shift end
  }

  @Test
  void lateAfter1920() {
    LocalDate day1 = LocalDate.of(Y, MO, D1);

    assertThat(ShiftConfig.isLate(ist(D1, 19, 25), day1)).isTrue();
    assertThat(ShiftConfig.lateMinutes(ist(D1, 19, 25), day1)).isEqualTo(5);

    assertThat(ShiftConfig.isLate(ist(D1, 19, 20), day1)).isFalse(); // at the threshold = on time
    assertThat(ShiftConfig.isLate(ist(D1, 19, 10), day1)).isFalse();
    assertThat(ShiftConfig.lateMinutes(ist(D1, 19, 10), day1)).isZero();

    // A post-midnight first clock-in (tail of the shift) is very late vs the shift-day's 19:20 threshold.
    assertThat(ShiftConfig.isLate(ist(D2, 2, 0), day1)).isTrue();
    assertThat(ShiftConfig.lateMinutes(ist(D2, 2, 0), day1)).isEqualTo(6 * 60 + 40); // 19:20 -> 02:00
  }
}
