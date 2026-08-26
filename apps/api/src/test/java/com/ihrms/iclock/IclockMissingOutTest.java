package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.iclock.IclockMissingOut.PunchFacts;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * "Missing OUT" membership and the completed-shift-day rule, as pure functions.
 *
 * <p>The clock is a parameter throughout: a rule about which shift has finished cannot be tested
 * against a clock that only moves forwards, and the interesting cases are all at boundaries.
 */
class IclockMissingOutTest {

  private static final ZoneId IST = ShiftConfig.ZONE;

  private static Instant ist(String local) {
    return LocalDateTime.parse(local).atZone(IST).toInstant();
  }

  private static PunchFacts punch(String local, String area, String direction) {
    return new PunchFacts(ist(local), area, direction);
  }

  // -------------------------------------------------- which day is complete

  @ParameterizedTest
  @CsvSource({
    // now (IST)              most recently COMPLETED shift day
    "2026-08-26T21:00:00,     2026-08-25", // mid-shift: the live day is not complete
    "2026-08-26T19:00:00,     2026-08-25", // the moment the shift opens
    "2026-08-27T01:30:00,     2026-08-25", // after midnight, still inside the same live shift
    "2026-08-27T03:59:59,     2026-08-25", // last second of the shift
    "2026-08-27T04:00:00,     2026-08-26", // the shift just closed — it is now the completed one
    "2026-08-27T05:47:00,     2026-08-26", // a late leaver has gone home
    "2026-08-27T11:29:59,     2026-08-26", // still before the day cut
    "2026-08-27T11:30:00,     2026-08-26", // past the cut: the new shift day has not started yet
    "2026-08-27T18:00:00,     2026-08-26", // evening, before tonight's shift opens
  })
  void theCompletedShiftDayFollowsWhetherTheShiftHasActuallyEnded(String now, String expected) {
    assertThat(IclockMissingOut.completedShiftDay(ist(now), IST))
        .isEqualTo(LocalDate.parse(expected));
  }

  @Test
  void theBoundaryIsShiftCloseNotTheDayCut() {
    // A minute before 04:00 the shift is still running; a minute after, it is the completed one. Getting
    // this wrong shows yesterday's list all evening, or today's half-finished list at 05:00.
    assertThat(IclockMissingOut.completedShiftDay(ist("2026-08-27T03:59:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-25"));
    assertThat(IclockMissingOut.completedShiftDay(ist("2026-08-27T04:01:00"), IST))
        .isEqualTo(LocalDate.parse("2026-08-26"));
  }

  // ------------------------------------------------------------- membership

  @Test
  void someoneWhoTappedOutAtTheGateIsNotMissing() {
    assertThat(
            IclockMissingOut.isMissingOut(
                List.of(
                    punch("2026-08-26T19:05:00", "GATE", "IN"),
                    punch("2026-08-27T03:50:00", "GATE", "OUT"))))
        .isFalse();
  }

  @Test
  void someoneStillInTheOfficeIsMissing() {
    assertThat(
            IclockMissingOut.isMissingOut(List.of(punch("2026-08-26T19:05:00", "GATE", "IN"))))
        .isTrue();
  }

  @Test
  void aDayEndingOnACafeteriaOutIsMissing() {
    // THE CASE THE INVERSION CREATES. A cafeteria OUT means they went back to their desk — an arrival
    // at work, not a departure from the building — so a day ending on one is the stuck-in-office case,
    // not a clean exit. Reading it as a departure would silently excuse exactly these people.
    assertThat(
            IclockMissingOut.isMissingOut(
                List.of(
                    punch("2026-08-26T19:05:00", "GATE", "IN"),
                    punch("2026-08-26T22:00:00", "CAFETERIA", "IN"),
                    punch("2026-08-26T22:30:00", "CAFETERIA", "OUT"))))
        .isTrue();
  }

  @Test
  void aDayEndingInTheCafeteriaIsMissing() {
    assertThat(
            IclockMissingOut.isMissingOut(
                List.of(
                    punch("2026-08-26T19:05:00", "GATE", "IN"),
                    punch("2026-08-27T02:00:00", "CAFETERIA", "IN"))))
        .isTrue();
  }

  @Test
  void someoneWhoNeverArrivedIsNotMissing() {
    assertThat(IclockMissingOut.isMissingOut(List.of())).isFalse();
    assertThat(IclockMissingOut.isMissingOut(null)).isFalse();
  }

  @Test
  void aDayOfOnlyExitsIsNotReportedAsMissingAnExit() {
    // An orphan OUT with no IN is a different problem and deserves a different story. Calling it
    // "missing an OUT" would be actively misleading about what happened.
    assertThat(
            IclockMissingOut.isMissingOut(List.of(punch("2026-08-27T03:50:00", "GATE", "OUT"))))
        .isFalse();
  }

  @Test
  void aReEntryAfterLeavingCountsAsStillInside() {
    // Out for dinner, back in, never left again — the last punch is an IN, so they are missing an exit.
    assertThat(
            IclockMissingOut.isMissingOut(
                List.of(
                    punch("2026-08-26T19:05:00", "GATE", "IN"),
                    punch("2026-08-26T21:00:00", "GATE", "OUT"),
                    punch("2026-08-26T21:40:00", "GATE", "IN"))))
        .isTrue();
  }

  @Test
  void theRuleIsNotVacuous() {
    // The vacuity negative: a rule that returned true for everybody would pass every positive case
    // above. The clean-exit day and the never-arrived day must come back false, and they do — asserted
    // here together so the pair cannot be deleted independently.
    List<PunchFacts> cleanExit =
        List.of(punch("2026-08-26T19:05:00", "GATE", "IN"), punch("2026-08-27T03:50:00", "GATE", "OUT"));
    List<PunchFacts> stuckInside = List.of(punch("2026-08-26T19:05:00", "GATE", "IN"));

    assertThat(IclockMissingOut.isMissingOut(cleanExit)).isFalse();
    assertThat(IclockMissingOut.isMissingOut(stuckInside)).isTrue();
    assertThat(IclockMissingOut.isMissingOut(cleanExit))
        .as("the rule must distinguish these two, not answer the same for both")
        .isNotEqualTo(IclockMissingOut.isMissingOut(stuckInside));
  }
}
