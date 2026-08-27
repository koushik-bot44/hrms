package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V45's SQL shift-day rule must agree with the Java one, instant for instant.
 *
 * <p>The migration cannot read {@link ShiftConfig#DAY_CUT} — it is a Java constant and V45 is SQL — so
 * the cut appears in both places. That is exactly the shape of the bug V45 exists to repair: a literal
 * that stopped agreeing with the rule around it. This test is the joint. If anyone moves the shift, the
 * derived {@code DAY_CUT} moves with it, this test fails, and the migration's literal has to be brought
 * along rather than quietly diverging.
 *
 * <p>Evaluated by PostgreSQL, not re-implemented here — re-implementing the SQL in Java would only prove
 * that two copies of the same mistake agree.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockShiftDayBackfillTest {

  @Autowired JdbcTemplate jdbc;

  private static final ZoneId IST = ShiftConfig.ZONE;

  /** The expression V45 uses, verbatim. Kept in one place so the test cannot drift from the migration. */
  private static final String SQL_RULE =
      "SELECT CASE WHEN (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Kolkata')::time < TIME '11:30' "
          + "THEN (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Kolkata')::date - 1 "
          + "ELSE (CAST(? AS timestamptz) AT TIME ZONE 'Asia/Kolkata')::date END";

  @Test
  void theMigrationsSqlRuleMatchesShiftConfigAtEveryBoundary() {
    List<String> instants =
        List.of(
            "2026-08-25T17:45:00", // early arrival
            "2026-08-25T19:00:00", // shift start
            "2026-08-25T19:15:00", // late threshold
            "2026-08-26T00:00:00", // past midnight
            "2026-08-26T03:59:00", // IN just before shift end
            "2026-08-26T03:59:59",
            "2026-08-26T04:00:00", // the closing OUT — the case V45 exists for
            "2026-08-26T05:47:00", // a late leaver
            "2026-08-26T11:29:59", // last moment before the cut
            "2026-08-26T11:30:00", // the cut
            "2026-08-26T17:45:00");

    for (String local : instants) {
      Instant at = java.time.LocalDateTime.parse(local).atZone(IST).toInstant();
      java.sql.Timestamp ts = java.sql.Timestamp.from(at);
      LocalDate fromSql =
          jdbc.queryForObject(SQL_RULE, java.sql.Date.class, ts, ts, ts).toLocalDate();

      assertThat(fromSql)
          .as("SQL and Java must agree on the shift-day for %s IST", local)
          .isEqualTo(ShiftConfig.shiftDateOf(at))
          .isEqualTo(IclockShiftDay.of(at, IST));
    }
  }

  @Test
  void theSqlLiteralStillMatchesTheDerivedConstant() {
    // If the shift moves, DAY_CUT moves — and V45's literal 11:30 must be updated to match. This is the
    // assertion that says so out loud instead of letting the two drift apart silently.
    assertThat(ShiftConfig.DAY_CUT)
        .as("V45 hard-codes TIME '11:30'; update the migration if this changes")
        .isEqualTo(LocalTime.of(11, 30));
  }

  @Test
  void everyEffectivePunchInTheDatabaseAgreesWithTheRule() {
    // The post-condition V45 asserts, re-asserted from the test suite so a later write path that stamps
    // shiftDate incorrectly is caught here rather than in a payroll dispute.
    //
    // THE CUT IS PER PERSON SINCE V47, and this assertion has to follow. It used to hard-code 11:30
    // for every row, which was true while everyone worked nights and became wrong the moment anyone
    // was assigned the day shift: a day-shift person cuts at 01:30, so their punches legitimately
    // disagree with the night rule. Left as it was, this test failed for correct data — and it did,
    // as soon as a sibling test committed day-profile punches into the same schema.
    //
    // A punch with no person (personId NULL, from the rolling-deploy window V44 documents) has no
    // profile to read, so the CASE falls through to the night default — which is what promotion
    // would have used for it.
    Long drift =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM "iclock_punches" p
              LEFT JOIN "iclock_people" pe ON pe."id" = p."personId"
             WHERE p."shiftDate" IS DISTINCT FROM (
                     CASE WHEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::time
                               < (CASE WHEN pe."shiftProfile" = 'DAY'
                                       THEN TIME '01:30' ELSE TIME '11:30' END)
                          THEN (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date - 1
                          ELSE (p."effectiveAt" AT TIME ZONE 'Asia/Kolkata')::date END)
            """,
            Long.class);
    assertThat(drift)
        .as("every punch must agree with ITS OWN person's shift-day cut")
        .isZero();
  }

  @Test
  void theSqlCutsMatchTheProfilesTheyClaimToMirror() {
    // The literals above are mirrors of the derivation, and mirrors drift. This is the assertion that
    // notices — the same job the 11:30 check does for V45, extended to the day profile.
    assertThat(IclockShiftProfile.NIGHT.dayCut())
        .as("the night literal in this file's SQL")
        .isEqualTo(LocalTime.of(11, 30));
    assertThat(IclockShiftProfile.DAY.dayCut())
        .as("the day literal in this file's SQL")
        .isEqualTo(LocalTime.of(1, 30));
  }
}
