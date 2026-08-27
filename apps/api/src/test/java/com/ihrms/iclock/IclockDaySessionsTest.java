package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The day view's session pairing, under the cafeteria inversion.
 *
 * <p>These are the cases that will occur on the FIRST night a cafeteria terminal is live. The old
 * pairer branched on direction alone and turned an ordinary night containing one break into "On site
 * 0m" plus two Estimated badges; the first test here is that exact scenario.
 */
class IclockDaySessionsTest {

  private static Instant ist(String local) {
    return LocalDateTime.parse(local.replace(' ', 'T')).atZone(ShiftConfig.ZONE).toInstant();
  }

  private static IclockDaySessions.Punch gateIn(String t) {
    return new IclockDaySessions.Punch(ist(t), "GATE", "IN");
  }

  private static IclockDaySessions.Punch gateOut(String t) {
    return new IclockDaySessions.Punch(ist(t), "GATE", "OUT");
  }

  /** Under the inversion this STARTS a break. */
  private static IclockDaySessions.Punch cafeIn(String t) {
    return new IclockDaySessions.Punch(ist(t), "CAFETERIA", "IN");
  }

  /** ...and this ends it. */
  private static IclockDaySessions.Punch cafeOut(String t) {
    return new IclockDaySessions.Punch(ist(t), "CAFETERIA", "OUT");
  }

  // ------------------------------------------------------------ THE NIGHT-ONE CASE

  @Test
  void anOrdinaryNightWithOneBreakIsWorkThenBreakThenWork() {
    List<IclockDaySessions.Punch> day = List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 22:00:00"),
        cafeOut("2026-08-27 22:30:00"),
        gateOut("2026-08-28 04:00:00"));

    var segments = IclockDaySessions.segment(day);

    assertThat(segments).hasSize(3);
    assertThat(segments.get(0))
        .as("work up to the break")
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-27 19:00:00"), ist("2026-08-27 22:00:00"), false, false));
    assertThat(segments.get(1))
        .as("the break itself")
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-27 22:00:00"), ist("2026-08-27 22:30:00"), false, true));
    assertThat(segments.get(2))
        .as("work resumes and runs to the gate exit")
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-27 22:30:00"), ist("2026-08-28 04:00:00"), false, false));

    assertThat(segments).as("nothing on a complete night is estimated")
        .noneMatch(IclockDaySessions.Segment::estimated);
    assertThat(IclockDaySessions.workedMinutes(segments)).isEqualTo(8 * 60 + 30);
    assertThat(IclockDaySessions.cafeteriaMinutes(segments)).isEqualTo(30);
  }

  @Test
  void vacuityNegative_treatingCafeteriaInAsAnArrivalIsWhatBrokeIt() {
    // The old rule, reproduced: branch on direction only. It is asserted here so the regression is
    // described rather than merely absent — if someone reinstates it, the test above dies and this
    // one explains why.
    List<IclockDaySessions.Punch> day = List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 22:00:00"),
        cafeOut("2026-08-27 22:30:00"),
        gateOut("2026-08-28 04:00:00"));

    long directionOnlyIns = day.stream().filter(p -> "IN".equals(p.direction())).count();
    assertThat(directionOnlyIns)
        .as("two punches say IN, but only ONE of them is an arrival")
        .isEqualTo(2);
    assertThat(IclockDaySessions.firstArrival(day))
        .as("the arrival is the gate one, never the cafeteria one")
        .isEqualTo(ist("2026-08-27 19:00:00"));
  }

  @Test
  void twoBreaksProduceFiveSegments() {
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 21:00:00"),
        cafeOut("2026-08-27 21:20:00"),
        cafeIn("2026-08-28 01:00:00"),
        cafeOut("2026-08-28 01:15:00"),
        gateOut("2026-08-28 04:00:00")));

    assertThat(segments).hasSize(5);
    assertThat(segments).noneMatch(IclockDaySessions.Segment::estimated);
    assertThat(IclockDaySessions.cafeteriaMinutes(segments)).isEqualTo(35);
    assertThat(IclockDaySessions.workedMinutes(segments)).isEqualTo(9 * 60 - 35);
  }

  // ------------------------------------------------------------ honest edges

  @Test
  void aDayWithNoGateExitLeavesTheLastWorkStretchOpenAndEstimated() {
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 22:00:00"),
        cafeOut("2026-08-27 22:30:00")));

    assertThat(segments).hasSize(3);
    var last = segments.get(2);
    assertThat(last.to()).as("never invented").isNull();
    assertThat(last.estimated()).isTrue();
    assertThat(last.cafeteria()).isFalse();
  }

  @Test
  void anUnpairedOutRendersAsAGapRatherThanBeingDropped() {
    // The mirror of the unpaired-IN rule, confirmed as a standing requirement.
    var segments = IclockDaySessions.segment(List.of(gateOut("2026-08-28 04:00:00")));

    assertThat(segments).hasSize(1);
    assertThat(segments.get(0).from()).isNull();
    assertThat(segments.get(0).to()).isEqualTo(ist("2026-08-28 04:00:00"));
    assertThat(segments.get(0).estimated()).isTrue();
  }

  // ------------------------------------------- THE CAFETERIA IS OUTSIDE THE GATE LINE

  @Test
  void vishnusRealBreak_gatePunchesInsideATripAreLegsOfIt() {
    // VERBATIM from production, pin 22193 on 2026-08-26 — the first night cafeteria terminals ran.
    // Read literally this is a 17-second break followed by a second arrival; it is one five-minute
    // coffee, and the two gate taps are the walk there and back.
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-26 02:41:10"),
        cafeIn("2026-08-26 03:39:32"),
        gateOut("2026-08-26 03:39:49"),
        gateIn("2026-08-26 03:44:08"),
        cafeOut("2026-08-26 03:44:25"),
        gateOut("2026-08-26 04:11:05")));

    assertThat(segments).hasSize(3);
    assertThat(segments.get(0))
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-26 02:41:10"), ist("2026-08-26 03:39:32"), false, false));
    assertThat(segments.get(1))
        .as("the break runs cafeteria-reader to cafeteria-reader, not gate to gate")
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-26 03:39:32"), ist("2026-08-26 03:44:25"), false, true));
    assertThat(segments.get(2))
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-26 03:44:25"), ist("2026-08-26 04:11:05"), false, false));

    assertThat(segments).noneMatch(IclockDaySessions.Segment::estimated);
    assertThat(IclockDaySessions.cafeteriaMinutes(segments))
        .as("a real coffee break, not seventeen seconds")
        .isEqualTo(4);
    assertThat(IclockDaySessions.lastDeparture(List.of(
        gateIn("2026-08-26 02:41:10"),
        cafeIn("2026-08-26 03:39:32"),
        gateOut("2026-08-26 03:39:49"),
        gateIn("2026-08-26 03:44:08"),
        cafeOut("2026-08-26 03:44:25"),
        gateOut("2026-08-26 04:11:05"))))
        .as("the departure is the gate exit AFTER the trip, not the one inside it")
        .isEqualTo(ist("2026-08-26 04:11:05"));
  }

  @Test
  void vacuityNegative_readingGatePunchesLiterallyShredsTheDay() {
    // What the previous rule produced for exactly those punches, asserted so the regression is
    // described rather than merely absent: a 17-second break and the day in two pieces.
    var literal = IclockDaySessions.segment(
        List.of(
            gateIn("2026-08-26 02:41:10"),
            cafeIn("2026-08-26 03:39:32"),
            gateOut("2026-08-26 03:39:49"),
            gateIn("2026-08-26 03:44:08"),
            cafeOut("2026-08-26 03:44:25"),
            gateOut("2026-08-26 04:11:05")),
        java.time.Duration.ZERO); // zero trip window == the old literal reading

    assertThat(literal.stream().filter(IclockDaySessions.Segment::cafeteria))
        .as("with no trip window the break collapses to the gate exit seconds later")
        .anySatisfy(s -> assertThat(s.to()).isEqualTo(ist("2026-08-26 03:39:49")));
    assertThat(literal).hasSizeGreaterThan(3);
  }

  @Test
  void aGateExitLongIntoABreakIsGoingHome_theCapDoesItsJob() {
    // The failure the cap exists to prevent: tap into the cafeteria, never tap back, leave. Without
    // it the departure is swallowed and the person shows on an open break for the rest of the shift.
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 21:00:00"),
        gateOut("2026-08-27 23:30:00")));

    assertThat(segments).hasSize(2);
    assertThat(segments.get(1))
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-27 21:00:00"), ist("2026-08-27 23:30:00"), false, true));
    assertThat(segments).noneMatch(IclockDaySessions.Segment::estimated);
  }

  @Test
  void aCafeteriaReturnWithNoRecordedDepartureIsFlaggedNotSwallowed() {
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeOut("2026-08-27 22:30:00"),
        gateOut("2026-08-28 04:00:00")));

    // The unmatched return is surfaced, and the work session it sits inside is NOT disturbed: they
    // were at their desk from 19:00 and never recorded stopping, so that stretch runs unbroken to
    // the gate exit. An earlier draft restarted work at the cafeteria tap, which silently deleted
    // the 19:00 start; this asserts it survives.
    assertThat(segments).hasSize(2);

    var work = segments.get(0);
    assertThat(work.cafeteria()).isFalse();
    assertThat(work.from()).isEqualTo(ist("2026-08-27 19:00:00"));
    assertThat(work.to()).isEqualTo(ist("2026-08-28 04:00:00"));

    var orphan = segments.get(1);
    assertThat(orphan.cafeteria()).isTrue();
    assertThat(orphan.from()).as("no departure was recorded, so none is invented").isNull();
    assertThat(orphan.estimated()).isTrue();
    assertThat(orphan.to()).isEqualTo(ist("2026-08-27 22:30:00"));
  }

  @Test
  void anUnmatchedCafeteriaReturnBeforeAnyArrivalDoesStartWork() {
    // The other half of the same rule: with nothing open, the return is the only evidence they are
    // at their desk, so work legitimately starts there.
    var segments = IclockDaySessions.segment(List.of(
        cafeOut("2026-08-27 22:30:00"), gateOut("2026-08-28 04:00:00")));

    assertThat(segments).hasSize(2);
    assertThat(segments.stream().filter(s -> !s.cafeteria()).findFirst().orElseThrow())
        .isEqualTo(new IclockDaySessions.Segment(
            ist("2026-08-27 22:30:00"), ist("2026-08-28 04:00:00"), false, false));
  }

  @Test
  void segmentsComeBackInChronologicalOrder() {
    // They are emitted when they CLOSE, so a work stretch ending at 04:00 would otherwise sort below
    // a break that closed at 22:30 inside it.
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 22:00:00"),
        cafeOut("2026-08-27 22:30:00"),
        gateOut("2026-08-28 04:00:00")));

    var starts = segments.stream()
        .map(s -> s.from() != null ? s.from() : s.to())
        .toList();
    assertThat(starts).isSorted();
  }

  @Test
  void twoCafeteriaEntriesWithNoReturnBetweenThemLeaveTheFirstOpen() {
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 21:00:00"),
        cafeIn("2026-08-27 21:05:00"),
        gateOut("2026-08-28 04:00:00")));

    assertThat(segments.stream().filter(s -> s.cafeteria() && s.estimated())).hasSize(1);
    assertThat(segments.get(segments.size() - 1).to()).isEqualTo(ist("2026-08-28 04:00:00"));
  }

  @Test
  void aMixedPunchIsSkippedRatherThanGuessed() {
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"),
        new IclockDaySessions.Punch(ist("2026-08-27 22:00:00"), "GATE", "MIXED"),
        gateOut("2026-08-28 04:00:00")));

    assertThat(segments)
        .as("a guessed direction would fabricate or destroy a session")
        .containsExactly(new IclockDaySessions.Segment(
            ist("2026-08-27 19:00:00"), ist("2026-08-28 04:00:00"), false, false));
  }

  @Test
  void anEmptyDayHasNoSegments() {
    assertThat(IclockDaySessions.segment(List.of())).isEmpty();
    assertThat(IclockDaySessions.segment(null)).isEmpty();
  }

  // ------------------------------------------------------------ first in / last out

  @Test
  void lastDepartureIgnoresTheWalkBackFromTheCafeteria() {
    // The concrete misreport: "Last out" used to show 22:30 — the moment they sat back down.
    List<IclockDaySessions.Punch> day = List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 22:00:00"),
        cafeOut("2026-08-27 22:30:00"),
        gateOut("2026-08-28 04:00:00"));

    assertThat(IclockDaySessions.lastDeparture(day)).isEqualTo(ist("2026-08-28 04:00:00"));
    assertThat(IclockDaySessions.firstArrival(day)).isEqualTo(ist("2026-08-27 19:00:00"));
  }

  @Test
  void somebodyStillInTheBuildingHasNoDeparture() {
    List<IclockDaySessions.Punch> day = List.of(
        gateIn("2026-08-27 19:00:00"),
        cafeIn("2026-08-27 22:00:00"),
        cafeOut("2026-08-27 22:30:00"));

    assertThat(IclockDaySessions.lastDeparture(day))
        .as("a cafeteria return must never be reported as leaving for the day")
        .isNull();
  }

  @Test
  void openBreaksAndOpenWorkContributeNothingToTheTotals() {
    var segments = IclockDaySessions.segment(List.of(
        gateIn("2026-08-27 19:00:00"), cafeIn("2026-08-27 22:00:00")));

    assertThat(IclockDaySessions.workedMinutes(segments)).isEqualTo(3 * 60);
    assertThat(IclockDaySessions.cafeteriaMinutes(segments))
        .as("an unfinished break is not yet a measurement")
        .isZero();
  }
}
