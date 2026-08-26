package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * "Exceeding break" membership, asserted as a pure function.
 *
 * <p>No Spring, no database, and no {@code Instant.now()} — the clock is a parameter, because a rule
 * about elapsed time cannot be tested against a clock that only moves forwards. Every boundary here is
 * a real operational decision: the threshold below which an absence is just a break, the cap above
 * which it is a departure, and the shift window outside which nobody is "away" in any meaningful sense.
 */
class IclockBreakAlertTest {

  private static final ZoneId IST = ShiftConfig.ZONE;
  private static final int MIN = 30;
  private static final int MAX = 120;

  private static Instant ist(String local) {
    return LocalDateTime.parse(local).atZone(IST).toInstant();
  }

  /** 21:00 IST — comfortably inside the 19:00 to 04:00 shift. */
  private static final Instant NOW = ist("2026-08-26T21:00:00");

  private static IclockBreakAlert.Where evaluate(Instant lastAt, String area, String dir, Instant now) {
    return IclockBreakAlert.evaluate(lastAt, area, dir, now, IST, MIN, MAX);
  }

  // ------------------------------------------------------------------ arrival

  @Test
  void someoneWhoNeverArrivedIsNotOnABreak() {
    // No punch at all means no absence to measure — they are simply not here.
    assertThat(evaluate(null, "GATE", "OUT", NOW)).isNull();
  }

  // -------------------------------------------------------------- away types

  @Test
  void aGateOutPastTheThresholdAlertsAsOutside() {
    assertThat(evaluate(NOW.minusSeconds(45 * 60), "GATE", "OUT", NOW))
        .isEqualTo(IclockBreakAlert.Where.OUTSIDE);
  }

  @Test
  void aCafeteriaInPastTheThresholdAlertsAsCafeteria() {
    // "IN for cafeteria is OUT for work" — the confirmed inversion. A cafeteria IN STARTS the break.
    assertThat(evaluate(NOW.minusSeconds(45 * 60), "CAFETERIA", "IN", NOW))
        .isEqualTo(IclockBreakAlert.Where.CAFETERIA);
  }

  @ParameterizedTest
  @CsvSource({
    "GATE,      IN",       // at their desk
    "CAFETERIA, OUT",      // back from the cafeteria, i.e. at their desk
  })
  void aPunchThatPutsThemAtWorkNeverAlerts(String area, String direction) {
    assertThat(evaluate(NOW.minusSeconds(90 * 60), area, direction, NOW)).isNull();
  }

  @Test
  void anUnclaimedTerminalsPunchCannotAlert() {
    // area and direction are null until a device is claimed; that is a device problem, not a break.
    assertThat(evaluate(NOW.minusSeconds(45 * 60), null, null, NOW)).isNull();
  }

  // -------------------------------------------------------------- thresholds

  @ParameterizedTest
  @CsvSource({
    "1,   false", // just stepped out
    "29,  false",
    "30,  false", // AT the threshold is still within a normal break
    "31,  true",  // one minute over
    "60,  true",
    "119, true",
    "120, false", // AT the cap the alert retires
    "180, false", // long gone
  })
  void theWindowIsOpenAboveTheThresholdAndClosedAtTheCap(int minutesAway, boolean shouldAlert) {
    var result = evaluate(NOW.minusSeconds(minutesAway * 60L), "GATE", "OUT", NOW);
    assertThat(result != null)
        .as("%d minutes away should %salert", minutesAway, shouldAlert ? "" : "not ")
        .isEqualTo(shouldAlert);
  }

  @Test
  void theCapExistsSoPeopleWhoWentHomeDoNotFillTheStripAllNight() {
    // The judgement call, pinned. Someone who left at 20:00 and is still gone at 23:00 is finished for
    // the day, not on a three-hour lunch. Without the cap the strip fills with people nobody can clear,
    // and an alert that cannot be cleared is one the operator stops reading.
    Instant leftAt = ist("2026-08-26T20:00:00");
    assertThat(evaluate(leftAt, "GATE", "OUT", ist("2026-08-26T20:45:00"))).isNotNull();
    assertThat(evaluate(leftAt, "GATE", "OUT", ist("2026-08-26T23:00:00"))).isNull();
  }

  // ------------------------------------------------------------ shift window

  @ParameterizedTest
  @CsvSource({
    "2026-08-26T18:59:00, false", // before the shift starts
    "2026-08-26T19:00:00, true",  // shift start
    "2026-08-26T23:30:00, true",
    "2026-08-27T00:30:00, true",  // AFTER MIDNIGHT — the wrap that a naive range test gets wrong
    "2026-08-27T03:59:00, true",  // last minute of the shift
    "2026-08-27T04:00:00, false", // shift end
    "2026-08-27T11:00:00, false", // the middle of the day: everyone is "away" and none of it matters
  })
  void alertsFireOnlyDuringTheShiftIncludingAfterMidnight(String nowLocal, boolean inShift) {
    Instant now = ist(nowLocal);
    var result = evaluate(now.minusSeconds(45 * 60), "GATE", "OUT", now);
    assertThat(result != null).as("at %s", nowLocal).isEqualTo(inShift);
  }

  @Test
  void theShiftWindowWrapsMidnightRatherThanBeingASingleComparison() {
    // Stated separately because getting this wrong makes the alert silent for the entire post-midnight
    // half of every shift — and it would look like "nobody takes long breaks after midnight".
    assertThat(IclockBreakAlert.withinShift(ist("2026-08-27T01:00:00"), IST)).isTrue();
    assertThat(IclockBreakAlert.withinShift(ist("2026-08-26T12:00:00"), IST)).isFalse();
  }

  // ------------------------------------------------------------------ elapsed

  @Test
  void elapsedIsWholeMinutesAndNeverNegative() {
    assertThat(IclockBreakAlert.elapsedMinutes(NOW.minusSeconds(47 * 60), NOW)).isEqualTo(47);
    // A device clock ahead of the server must not render as "-3m away".
    assertThat(IclockBreakAlert.elapsedMinutes(NOW.plusSeconds(180), NOW)).isZero();
  }

  @Test
  void awayKindIsExhaustiveAboutWhatCountsAsAway() {
    assertThat(IclockBreakAlert.awayKind("GATE", "OUT")).isEqualTo(IclockBreakAlert.Where.OUTSIDE);
    assertThat(IclockBreakAlert.awayKind("CAFETERIA", "IN")).isEqualTo(IclockBreakAlert.Where.CAFETERIA);
    assertThat(IclockBreakAlert.awayKind("GATE", "IN")).isNull();
    assertThat(IclockBreakAlert.awayKind("CAFETERIA", "OUT")).isNull();
    assertThat(IclockBreakAlert.awayKind("MIXED", "OUT")).isNull();
    assertThat(IclockBreakAlert.awayKind(null, "OUT")).isNull();
  }
}
