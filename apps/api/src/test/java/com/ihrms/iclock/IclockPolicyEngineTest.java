package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ported rulebook, pinned against the legacy analyzer's arithmetic.
 *
 * <p>These numbers are the contract with the operator: a month computed here has to reconcile with a
 * month the analyzer produced, or nobody will trust the replacement. Where this engine deliberately
 * DIVERGES — unclosed sessions, cafeteria-aware breaks — the divergence has its own test saying so.
 */
class IclockPolicyEngineTest {

  private static final ZoneId IST = ShiftConfig.ZONE;
  private static final IclockPolicyEngine.Policy P = IclockPolicyEngine.Policy.defaults();

  private static Instant ist(String local) {
    return LocalDateTime.parse(local.replace(' ', 'T')).atZone(IST).toInstant();
  }

  private static IclockDaySessions.Punch gi(String t) {
    return new IclockDaySessions.Punch(ist(t), "GATE", "IN");
  }

  private static IclockDaySessions.Punch go(String t) {
    return new IclockDaySessions.Punch(ist(t), "GATE", "OUT");
  }

  private static IclockDaySessions.Punch ci(String t) {
    return new IclockDaySessions.Punch(ist(t), "CAFETERIA", "IN");
  }

  private static IclockDaySessions.Punch co(String t) {
    return new IclockDaySessions.Punch(ist(t), "CAFETERIA", "OUT");
  }

  private static IclockPolicyEngine.DayFacts day(
      String date, List<IclockDaySessions.Punch> punches, int exemptMin, boolean dayExempt) {
    return IclockPolicyEngine.day(
        new IclockPolicyEngine.DayInput(
            LocalDate.parse(date), punches, IclockShiftProfile.NIGHT, exemptMin, dayExempt),
        P,
        IST);
  }

  private static IclockPolicyEngine.DayFacts day(String date, List<IclockDaySessions.Punch> p) {
    return day(date, p, 0, false);
  }

  // ---------------------------------------------------------------- defaults

  @Test
  void theDefaultsAreTheAnalyzersDefaults() {
    assertThat(P.shiftHours()).isEqualTo(9);
    assertThat(P.allowedBreakMin()).isEqualTo(60);
    assertThat(P.minBreakMin()).isEqualTo(5);
    assertThat(P.lopAfterLateDays()).isEqualTo(3);
    assertThat(P.expectedWorkMinPerDay()).as("9h shift less the paid hour").isEqualTo(480);
    assertThat(P.officeMinPerDay()).isEqualTo(540);
  }

  // ---------------------------------------------------------------- one day

  @Test
  void anOrdinaryNightWithOneBreak() {
    var d = day("2026-08-26", List.of(
        gi("2026-08-26 19:00:00"),
        ci("2026-08-26 22:00:00"),
        co("2026-08-26 22:30:00"),
        go("2026-08-27 04:00:00")));

    assertThat(d.status()).isEqualTo(IclockPolicyEngine.Status.PRESENT);
    assertThat(d.workedRawMin()).as("8h30 of work either side of the break").isEqualTo(510);
    assertThat(d.workedMin()).isEqualTo(510);
    assertThat(d.breakMin()).isEqualTo(30);
    assertThat(d.cafeteriaMin()).isEqualTo(30);
    assertThat(d.late()).isFalse();
    assertThat(d.hasMissingPunch()).isFalse();
    assertThat(d.capped()).isFalse();
  }

  @Test
  void workIsCappedAtTheShiftLength() {
    // capWork in the analyzer. Someone in the building for twelve hours is paid for nine.
    var d = day("2026-08-26", List.of(gi("2026-08-26 18:00:00"), go("2026-08-27 06:00:00")));

    assertThat(d.workedRawMin()).isEqualTo(720);
    assertThat(d.workedMin()).as("capped to the 9h shift").isEqualTo(540);
    assertThat(d.capped()).isTrue();
  }

  @Test
  void aGapShorterThanTheFloorIsNotABreak() {
    var d = day("2026-08-26", List.of(
        gi("2026-08-26 19:00:00"),
        ci("2026-08-26 22:00:00"),
        co("2026-08-26 22:03:00"),
        go("2026-08-27 04:00:00")));

    assertThat(d.breakMin()).as("three minutes is not a break").isZero();
  }

  @Test
  void anUnclosedSessionIsWorthNothingAndFlagsTheDay() {
    // THE DELIBERATE DIVERGENCE. The analyzer estimated the missing OUT at the midpoint between two
    // INs and counted it as worked time — a guess feeding payroll. Here it is worth zero and said so.
    var d = day("2026-08-26", List.of(gi("2026-08-26 19:00:00")));

    assertThat(d.status()).isEqualTo(IclockPolicyEngine.Status.PRESENT);
    assertThat(d.workedMin()).isZero();
    assertThat(d.hasMissingPunch()).isTrue();
  }

  @Test
  void asinglePunchStillCountsAsPresent() {
    // The analyzer's "single punch = Present override". They were at work; erasing them is worse.
    assertThat(day("2026-08-26", List.of(gi("2026-08-26 19:00:00"))).status())
        .isEqualTo(IclockPolicyEngine.Status.PRESENT);
  }

  @Test
  void aSaturdayWithNoPunchesIsAWeeklyOffNotAnAbsence() {
    assertThat(day("2026-08-29", List.of()).status())
        .isEqualTo(IclockPolicyEngine.Status.WEEKLY_OFF);
  }

  @Test
  void aWeekdayWithNoPunchesIsAnAbsence() {
    assertThat(day("2026-08-26", List.of()).status())
        .isEqualTo(IclockPolicyEngine.Status.ABSENT);
  }

  @Test
  void anExemptedEmptyDayIsAHoliday() {
    assertThat(day("2026-08-26", List.of(), 0, true).status())
        .isEqualTo(IclockPolicyEngine.Status.HOLIDAY);
  }

  // ---------------------------------------------------------------- lateness

  @Test
  void arrivingAfterTheThresholdIsLate_andLatenessIsMeasuredFromSHIFTSTART() {
    // 19:00 shift, 19:15 threshold. Arriving 19:20 is late by TWENTY minutes, not five: the analyzer
    // measures from shift start once the threshold is crossed. Confusing the two under-reports every
    // late arrival by the grace period.
    var d = day("2026-08-26", List.of(gi("2026-08-26 19:20:00"), go("2026-08-27 04:00:00")));

    assertThat(d.late()).isTrue();
    assertThat(d.lateMin()).isEqualTo(20);
  }

  @Test
  void arrivingInsideTheGraceIsNotLateAtAll() {
    var d = day("2026-08-26", List.of(gi("2026-08-26 19:10:00"), go("2026-08-27 04:00:00")));
    assertThat(d.late()).isFalse();
    assertThat(d.lateMin()).isZero();
  }

  @Test
  void exactlyOnTheThresholdIsNotLate() {
    var d = day("2026-08-26", List.of(gi("2026-08-26 19:15:00"), go("2026-08-27 04:00:00")));
    assertThat(d.late()).as("after the threshold, not at it").isFalse();
  }

  @Test
  void aPersonalExemptionReplacesTheThresholdRatherThanStacking() {
    // 30 minutes forgiven. Arriving 19:20 is 20 raw minutes late, all inside the exemption.
    var forgiven = day("2026-08-26", List.of(gi("2026-08-26 19:20:00")), 30, false);
    assertThat(forgiven.late()).isFalse();
    assertThat(forgiven.lateMin()).isZero();

    // Arriving 19:40 is 40 raw; 10 survive the exemption and they are the lateness.
    var over = day("2026-08-26", List.of(gi("2026-08-26 19:40:00")), 30, false);
    assertThat(over.late()).isTrue();
    assertThat(over.lateMin())
        .as("the remainder after the exemption, NOT reduced again by the grace")
        .isEqualTo(10);
  }

  @Test
  void anExemptedDayAssessesNoLateness() {
    var d = day("2026-08-26", List.of(gi("2026-08-26 21:00:00"), go("2026-08-27 04:00:00")), 0, true);
    assertThat(d.late()).isFalse();
    assertThat(d.lateMin()).isZero();
  }

  // ---------------------------------------------------------------- the month

  private static IclockPolicyEngine.PeriodFacts monthWithLateDays(int lateCount) {
    List<IclockPolicyEngine.DayFacts> days = new ArrayList<>();
    LocalDate d = LocalDate.parse("2026-08-03"); // a Monday
    for (int i = 0; i < 20; i++) {
      boolean late = i < lateCount;
      String date = d.plusDays(i).toString();
      days.add(day(date, List.of(
          gi(date + " " + (late ? "19:30:00" : "19:00:00")),
          go(d.plusDays(i + 1) + " 04:00:00"))));
    }
    return IclockPolicyEngine.period(days, P);
  }

  @Test
  void lopStartsOnTheFOURTHLateDay() {
    // The rule employees are told: "only the first three late logins are permitted. From the fourth
    // occurrence onwards, each late login is treated as one day of Leave Without Pay."
    assertThat(IclockPolicyEngine.lopDays(0, P)).isZero();
    assertThat(IclockPolicyEngine.lopDays(3, P)).as("three is still free").isZero();
    assertThat(IclockPolicyEngine.lopDays(4, P)).as("the fourth costs a day").isEqualTo(1);
    assertThat(IclockPolicyEngine.lopDays(9, P)).isEqualTo(6);
  }

  @Test
  void lopIsCountedInDaysLateNotMinutesLate() {
    // Four four-minute latenesses cost a day. That is the policy as written, and it is worth having a
    // test say so out loud before someone "fixes" it into a minutes-based rule.
    var month = monthWithLateDays(4);
    assertThat(month.lateDays()).isEqualTo(4);
    assertThat(month.lopDays()).isEqualTo(1);
  }

  @Test
  void theMonthRollsUpTheWayTheAnalyzerDid() {
    var month = monthWithLateDays(5);

    assertThat(month.presentDays()).isEqualTo(20);
    assertThat(month.lateDays()).isEqualTo(5);
    assertThat(month.expectedWorkMin()).as("20 present days x 480").isEqualTo(9600);
    assertThat(month.allowedBreakMin()).as("20 x the paid hour").isEqualTo(1200);
    assertThat(month.officeMin()).as("20 x 540").isEqualTo(10800);
    assertThat(month.avgLateMin()).as("every late day was 30 minutes").isEqualTo(30);
    assertThat(month.totalLateMin()).isEqualTo(150);
    assertThat(month.lopDays()).isEqualTo(2);
    assertThat(month.unproductiveMin()).as("breaks plus lateness").isEqualTo(150);
    assertThat(month.productiveMin()).isEqualTo(10800 - 150);
  }

  @Test
  void excessBreakIsWhatRunsOverThePaidHourAcrossTheMonth() {
    List<IclockPolicyEngine.DayFacts> days = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      String date = LocalDate.parse("2026-08-03").plusDays(i).toString();
      String next = LocalDate.parse("2026-08-04").plusDays(i).toString();
      days.add(day(date, List.of(
          gi(date + " 19:00:00"),
          ci(date + " 22:00:00"),
          co(date + " 23:30:00"), // a 90-minute break
          go(next + " 04:00:00"))));
    }
    var period = IclockPolicyEngine.period(days, P);

    assertThat(period.totalBreakMin()).isEqualTo(270);
    assertThat(period.allowedBreakMin()).isEqualTo(180);
    assertThat(period.excessBreakMin()).as("90 minutes over three days").isEqualTo(90);
  }

  @Test
  void workingDaysExcludeWeeklyOffsAndHolidays() {
    var period = IclockPolicyEngine.period(List.of(
        day("2026-08-03", List.of(gi("2026-08-03 19:00:00"), go("2026-08-04 04:00:00"))),
        day("2026-08-08", List.of()),          // Saturday
        day("2026-08-09", List.of()),          // Sunday
        day("2026-08-15", List.of(), 0, true), // holiday
        day("2026-08-04", List.of())), P);     // a genuine absence

    assertThat(period.totalDays()).isEqualTo(5);
    assertThat(period.weeklyOffDays()).isEqualTo(2);
    assertThat(period.holidayDays()).isEqualTo(1);
    assertThat(period.absentDays()).isEqualTo(1);
    assertThat(period.workingDays()).as("total less weekly-offs and holidays").isEqualTo(2);
  }

  @Test
  void lateDatesComeBackSortedForTheWarningMail() {
    var period = IclockPolicyEngine.period(List.of(
        day("2026-08-05", List.of(gi("2026-08-05 19:30:00"), go("2026-08-06 04:00:00"))),
        day("2026-08-03", List.of(gi("2026-08-03 19:30:00"), go("2026-08-04 04:00:00"))),
        day("2026-08-04", List.of(gi("2026-08-04 19:30:00"), go("2026-08-05 04:00:00")))), P);

    assertThat(period.lateDates()).containsExactly(
        LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-05"));
  }

  @Test
  void anEmptyPeriodDividesByNothingAndSaysZero() {
    var period = IclockPolicyEngine.period(List.of(), P);
    assertThat(period.productivePct()).isZero();
    assertThat(period.workedPct()).isZero();
    assertThat(period.avgLateMin()).isZero();
    assertThat(period.lopDays()).isZero();
  }
}
