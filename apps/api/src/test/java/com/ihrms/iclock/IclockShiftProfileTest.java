package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The shift-day cut, pinned for BOTH profiles.
 *
 * <p>These run with no Spring context and no database: the derivation is the whole behaviour, so it
 * should be assertable without either. The night cases are the ones P1b.1 shipped, re-asserted through
 * the profile so the refactor cannot quietly move them.
 */
class IclockShiftProfileTest {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  /**
   * Written as date+time in IST rather than a UTC literal, because UTC arithmetic done by hand is how a
   * boundary test ends up confidently asserting the wrong boundary.
   */
  private static Instant ist(String date, String time) {
    return LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(IST).toInstant();
  }

  // ---------------------------------------------------------------- the derivation

  @Test
  void nightCutsAtTheMidpointOfItsNonWorkingWindow() {
    assertThat(IclockShiftProfile.NIGHT.dayCut()).isEqualTo(LocalTime.of(11, 30));
  }

  @Test
  void dayCutsAtTheMidpointOfItsOwnNonWorkingWindow() {
    // 18:00 plus half of the 15h gap = 01:30, wrapping past midnight. One formula, no branch.
    assertThat(IclockShiftProfile.DAY.dayCut()).isEqualTo(LocalTime.of(1, 30));
  }

  @Test
  void bothShiftsAreNineHoursLong_theWrapDoesNotMakeOneNegative() {
    assertThat(IclockShiftProfile.NIGHT.length().toHours()).isEqualTo(9);
    assertThat(IclockShiftProfile.DAY.length().toHours()).isEqualTo(9);
  }

  @Test
  void nightProfileAgreesWithTheGlobalConstantItReplaces() {
    // The global becomes the default profile. If someone edits ShiftConfig without the profile, or the
    // profile without ShiftConfig, this is the test that notices.
    assertThat(IclockShiftProfile.NIGHT.dayCut()).isEqualTo(ShiftConfig.DAY_CUT);
    assertThat(IclockShiftProfile.NIGHT.start()).isEqualTo(ShiftConfig.SHIFT_START);
    assertThat(IclockShiftProfile.NIGHT.end()).isEqualTo(ShiftConfig.SHIFT_END);
    assertThat(IclockShiftProfile.NIGHT.lateThreshold(LocalDate.parse("2026-08-27"), IST))
        .isEqualTo(ist("2026-08-27", "19:15"));
  }

  // ---------------------------------------------------------------- THE GS MIRROR CASE

  @Test
  void gsMirror_dayShiftInAndOutLandOnTheSameShiftDay() {
    // The case that motivated per-person shifts: 09:00 in, 18:00 out. Under the day profile they pair.
    LocalDate expected = LocalDate.parse("2026-08-27");
    assertThat(IclockShiftProfile.DAY.shiftDateOf(ist("2026-08-27", "09:00"), IST))
        .as("day-shift arrival")
        .isEqualTo(expected);
    assertThat(IclockShiftProfile.DAY.shiftDateOf(ist("2026-08-27", "18:00"), IST))
        .as("day-shift departure — the SAME day, which is the entire point")
        .isEqualTo(expected);
  }

  @Test
  void gsMirror_underTheNightProfileTheSamePairSplits() {
    // THE VACUITY NEGATIVE. If this ever reads as "same day", the day profile is decorative and the
    // test above proves nothing. 09:00 is before the 11:30 night cut, so it files a day EARLIER than
    // the 18:00 that follows it — an unpaired IN plus an orphan OUT, every single day.
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "09:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "18:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-27"));
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "09:00"), IST))
        .as("the defect: a day-shift pair straddles two shift days under the night cut")
        .isNotEqualTo(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "18:00"), IST));
  }

  @Test
  void dayShiftOvertimePastMidnightStaysWithTheDayItStarted() {
    // 00:30 is before the 01:30 day cut, so it belongs to the previous date — the mirror of the night
    // shift's 03:00 tail.
    assertThat(IclockShiftProfile.DAY.shiftDateOf(ist("2026-08-28", "00:30"), IST))
        .isEqualTo(LocalDate.parse("2026-08-27"));
  }

  // ---------------------------------------------------------------- the night boundaries P1b.1 pinned

  @Test
  void night_closingOutAtExactlyFourFilesOnTheShiftThatIsClosing() {
    // The P1b.1 defect in one assertion: 04:00:00 is the moment the shift ends, and it belongs to the
    // shift that just ended — not to the next one.
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "04:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
  }

  @Test
  void night_lateStragglerAtFiveFortySevenStillBelongsToTheClosingShift() {
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "05:47"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
  }

  @Test
  void night_earlyArrivalAtQuarterToSixOpensTheNewShiftDay() {
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "17:45"), IST))
        .isEqualTo(LocalDate.parse("2026-08-27"));
  }

  @Test
  void night_threeFiftyNineIsStillTheNightBefore() {
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "03:59"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
  }

  @Test
  void night_theCutItselfIsTheFirstInstantOfTheNewShiftDay() {
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "11:29:59"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
    assertThat(IclockShiftProfile.NIGHT.shiftDateOf(ist("2026-08-27", "11:30:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-27"));
  }

  // ---------------------------------------------------------------- windows and completed days

  @Test
  void withinShiftIsTwoRangesForNightAndOneForDay() {
    assertThat(IclockShiftProfile.NIGHT.withinShift(ist("2026-08-27", "22:00"), IST)).isTrue();
    assertThat(IclockShiftProfile.NIGHT.withinShift(ist("2026-08-27", "03:00"), IST))
        .as("post-midnight half — the range a single comparison would silence")
        .isTrue();
    assertThat(IclockShiftProfile.NIGHT.withinShift(ist("2026-08-27", "12:00"), IST)).isFalse();

    assertThat(IclockShiftProfile.DAY.withinShift(ist("2026-08-27", "12:00"), IST)).isTrue();
    assertThat(IclockShiftProfile.DAY.withinShift(ist("2026-08-27", "22:00"), IST)).isFalse();
    assertThat(IclockShiftProfile.DAY.withinShift(ist("2026-08-27", "03:00"), IST))
        .as("a day-shift break alert must not fire all night")
        .isFalse();
  }

  @Test
  void completedShiftDay_night() {
    // Mirrors the three cases IclockMissingOut documents.
    assertThat(IclockShiftProfile.NIGHT.completedShiftDay(ist("2026-08-27", "21:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
    assertThat(IclockShiftProfile.NIGHT.completedShiftDay(ist("2026-08-27", "05:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
    assertThat(IclockShiftProfile.NIGHT.completedShiftDay(ist("2026-08-27", "12:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
  }

  @Test
  void completedShiftDay_day() {
    // Midday: today's day shift is still running, so the completed one is yesterday.
    assertThat(IclockShiftProfile.DAY.completedShiftDay(ist("2026-08-27", "12:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
    // Evening: it finished at 18:00, so today counts.
    assertThat(IclockShiftProfile.DAY.completedShiftDay(ist("2026-08-27", "19:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-27"));
    // Half past midnight: still inside the previous day's overtime tail, and that day IS complete.
    assertThat(IclockShiftProfile.DAY.completedShiftDay(ist("2026-08-28", "00:30"), IST))
        .isEqualTo(LocalDate.parse("2026-08-27"));
  }

  @Test
  void endOfAdvancesADayOnlyForTheOvernightShift() {
    LocalDate d = LocalDate.parse("2026-08-27");
    assertThat(IclockShiftProfile.NIGHT.endOf(d, IST)).isEqualTo(ist("2026-08-28", "04:00"));
    assertThat(IclockShiftProfile.DAY.endOf(d, IST)).isEqualTo(ist("2026-08-27", "18:00"));
  }

  @Test
  void unknownProfileNamesFallBackToNight_theSafeDefault() {
    // An unrecognised value must never silently re-date someone. Night is current behaviour, and
    // current behaviour is what the safe default has to be.
    assertThat(IclockShiftProfile.byName("DAY")).isEqualTo(IclockShiftProfile.DAY);
    assertThat(IclockShiftProfile.byName("NIGHT")).isEqualTo(IclockShiftProfile.NIGHT);
    assertThat(IclockShiftProfile.byName("GS")).isEqualTo(IclockShiftProfile.NIGHT);
    assertThat(IclockShiftProfile.byName(null)).isEqualTo(IclockShiftProfile.NIGHT);
  }
}
