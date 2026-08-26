package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.dto.IclockAdminDtos.SweepResult;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The operator's sweep must never reach the pre-adoption archive.
 *
 * <p>Backfill is off by policy and the raw archive is retained, not derived. The unbounded
 * {@link IclockInboxService#sweep} exists only for the one-shot, flag-gated
 * {@link IclockBackfillRunner}; the HTTP endpoint an operator can click must use the claim-bounded
 * variant. Under P1a the distinction did not bite, because with no roster every punch died at
 * UNKNOWN_PIN however far back the query reached — P1b is what makes it live, against ~33,000
 * archived punches on this deployment.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockSweepBoundTest {

  @Autowired IclockInboxService inbox;
  @Autowired IclockRosterService roster;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockPersonRepository people;
  @Autowired IclockRawPunchRepository rawPunches;
  @Autowired IclockPunchRepository punches;
  @Autowired JdbcTemplate jdbc;

  private static final DateTimeFormatter WIRE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Kolkata"));

  /** The moment the terminal was adopted. Everything before it is archive. */
  private static final Instant CLAIM = Instant.parse("2026-08-20T03:30:00Z");

  private String siteId;
  private IclockDevice device;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"iclock_punch_members\",\"iclock_punches\",\"iclock_raw_punches\","
            + "\"iclock_people\",\"iclock_employee_pins\",\"iclock_devices\","
            + "\"iclock_site_companies\",\"iclock_sites\" RESTART IDENTITY CASCADE");

    IclockSite site = new IclockSite();
    site.setName("Sweep Bound Site");
    siteId = sites.save(site).getId();

    IclockDevice d = new IclockDevice();
    d.setSerialNumber("ZZTESTSWEEP");
    d.setStatus("CLAIMED");
    d.setSiteId(siteId);
    d.setArea("GATE");
    d.setDirection("IN");
    d.setClaimedAt(CLAIM);
    device = devices.save(d);

    IclockPerson p = new IclockPerson();
    p.setSiteId(siteId);
    p.setPin("7001");
    p.setName("Sweep Tester");
    people.save(p);
  }

  @Test
  void theOperatorSweepPromotesTheLiveWindowAndLeavesTheArchiveAlone() {
    // Three punches before adoption — the archive — and one after.
    raw("7001", CLAIM.minusSeconds(86_400), "archive-1");
    raw("7001", CLAIM.minusSeconds(7_200), "archive-2");
    raw("7001", CLAIM.minusSeconds(3_600), "archive-3");
    raw("7001", CLAIM.plusSeconds(600), "live-1");

    SweepResult result = inbox.sweepSinceClaim(5_000);

    // Only the live punch is even looked at. Without the bound the archive would go FIRST: the
    // unbounded query is ordered oldest-first, so the very rows policy says to leave alone are the
    // ones a limit would spend itself on.
    assertThat(result.scanned()).isEqualTo(1);
    assertThat(result.promoted()).isEqualTo(1);
    assertThat(punches.count()).isEqualTo(1);
    assertThat(punches.findAll().get(0).getPunchedAt()).isAfterOrEqualTo(CLAIM);
  }

  @Test
  void theUnboundedBackfillPrimitiveStillReachesEverything() {
    // The flag-gated runner depends on this reaching the whole archive; the point of the fix is that
    // the two are DIFFERENT methods, not that backfill became impossible.
    raw("7001", CLAIM.minusSeconds(86_400), "archive-1");
    raw("7001", CLAIM.plusSeconds(600), "live-1");

    SweepResult result = inbox.sweep(5_000);

    assertThat(result.scanned()).isEqualTo(2);
  }

  /**
   * THE POLICY PIN. Backfill is off, and this is the executable form of that ruling.
   *
   * <p>Every operator-reachable promotion path is driven against a database that is mostly archive,
   * and the invariant asserted afterwards is absolute: no effective punch may exist whose raw row was
   * received before the site's earliest {@code claimedAt}. Not "the current code happens not to" —
   * the property itself, checked against every row in the table.
   *
   * <p>Written this way on purpose. The backfill ruling has so far lived in a decision log and in
   * reviewers' memory, which is exactly what a refactor cannot consult. Any future change that widens
   * a query, drops a bound, or points an endpoint back at the unbounded primitive fails here and says
   * which punch crossed the line.
   */
  @Test
  void noOperatorPathCanEverPromoteAPunchFromBeforeTheClaim() {
    // A realistic shape: a deep archive, then a handful since adoption.
    for (int i = 1; i <= 25; i++) {
      raw("7001", CLAIM.minusSeconds(3600L * i), "archive-" + i);
    }
    raw("7001", CLAIM.plusSeconds(60), "live-1");
    raw("7001", CLAIM.plusSeconds(3600), "live-2");

    // Drive every promotion path an operator can actually reach, repeatedly and in both orders —
    // idempotency must not become a way to creep backwards one batch at a time.
    inbox.sweepSinceClaim(5_000);
    roster.reresolveSinceClaim(siteId, 20_000);
    inbox.sweepSinceClaim(5_000);
    roster.reresolveSinceClaim(siteId, 20_000);

    assertThat(punches.count()).isEqualTo(2);

    // The invariant, checked against the table rather than against the code that wrote it.
    Long violations =
        jdbc.queryForObject(
            """
            SELECT count(*)
              FROM "iclock_punches" p
              JOIN "iclock_punch_members" m ON m."punchId" = p."id"
              JOIN "iclock_raw_punches" r ON r."id" = m."rawPunchId"
             WHERE r."receivedAt" < (
                     SELECT min(d."claimedAt") FROM "iclock_devices" d
                      WHERE d."siteId" = ? AND d."claimedAt" IS NOT NULL)
            """,
            Long.class,
            siteId);
    assertThat(violations)
        .as("an effective punch was derived from a raw row older than the earliest device claim")
        .isZero();

    // And the archive itself is untouched — retained, never consumed.
    assertThat(rawPunches.count()).isEqualTo(27);
  }

  @Test
  void withNoClaimedDeviceTheSweepDoesNothingRatherThanFallingBackToTheArchive() {
    raw("7001", CLAIM.minusSeconds(86_400), "archive-1");
    // Unclaiming is all-or-nothing: iclock_devices_claim_complete (V43) requires siteId, area,
    // direction and claimedAt to be null together with the UNCLAIMED status.
    device.setStatus("UNCLAIMED");
    device.setSiteId(null);
    device.setArea(null);
    device.setDirection(null);
    device.setClaimedAt(null);
    devices.save(device);

    SweepResult result = inbox.sweepSinceClaim(5_000);

    assertThat(result.scanned()).isZero();
    assertThat(result.promoted()).isZero();
    assertThat(punches.count()).isZero();
  }

  private void raw(String pin, Instant at, String line) {
    IclockRawPunch r = new IclockRawPunch();
    r.setDeviceId(device.getId());
    r.setSerialNumber(device.getSerialNumber());
    r.setDevicePin(pin);
    r.setPunchedAtRaw(WIRE.format(at));
    r.setRawLine(line);
    r.setLineNumber(1);
    r.setDedupeKey("sweep-" + line);
    IclockRawPunch saved = rawPunches.save(r);
    // receivedAt is what the claim window is measured against, and it defaults to now() on insert.
    jdbc.update(
        "UPDATE \"iclock_raw_punches\" SET \"receivedAt\" = ? WHERE \"id\" = ?",
        java.sql.Timestamp.from(at), saved.getId());
  }
}
