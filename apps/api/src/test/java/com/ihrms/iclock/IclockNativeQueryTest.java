package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockPunchMember;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchMemberRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Every native query in the iClock module, executed against POPULATED data.
 *
 * <p><b>Why this class exists.</b> A native query is the one thing in this codebase that the compiler,
 * Hibernate's {@code ddl-auto: validate} and a mock-based test all agree to ignore. It is only checked
 * when Postgres actually runs it against rows. Two real production incidents came from exactly that
 * gap, and neither would have been caught by a test over an empty table:
 *
 * <ul>
 *   <li>{@code findBurstCandidate} threw {@code operator does not exist: timestamp with time zone >=
 *       interval} — a query planning error, so an empty table would have failed too, but only if some
 *       test had run it at all.
 *   <li>{@code findUnmappedPinSummarySince} returned {@link java.time.OffsetDateTime} where the caller
 *       cast to {@link java.sql.Timestamp}. That is a DRIVER TYPE mismatch: it can only surface when a
 *       row comes back, so a zero-row test passes and the operator's main work surface still 500s.
 *   </li>
 * </ul>
 *
 * <p>Hence the standing rule this class implements: every native-query read path gets at least one
 * test against populated data. Each test below asserts on values read out of the result — not merely
 * that the call returned — because reading the value is what exercises the driver mapping.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockNativeQueryTest {

  @Autowired IclockSiteRepository sites;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockPersonRepository people;
  @Autowired IclockRawPunchRepository rawPunches;
  @Autowired IclockPunchRepository punches;
  @Autowired IclockPunchMemberRepository members;
  @Autowired JdbcTemplate jdbc;

  private static final Instant T0 = Instant.parse("2026-08-20T03:30:00Z"); // 09:00 IST

  /** The terminal's own wire format for a punch time, in the site's zone. */
  private static final java.time.format.DateTimeFormatter WIRE_FORMAT =
      java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
          .withZone(java.time.ZoneId.of("Asia/Kolkata"));

  private String siteId;
  private String deviceId;
  private String otherDeviceId;
  private String personId;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"iclock_punch_members\",\"iclock_punches\",\"iclock_raw_punches\","
            + "\"iclock_people\",\"iclock_employee_pins\",\"iclock_devices\","
            + "\"iclock_site_companies\",\"iclock_sites\",\"iclock_request_logs\""
            + " RESTART IDENTITY CASCADE");

    IclockSite site = new IclockSite();
    site.setName("Native Query Test Site");
    siteId = sites.save(site).getId();

    deviceId = device("ZZTESTNQ1", "GATE", "IN").getId();
    otherDeviceId = device("ZZTESTNQ2", "GATE", "OUT").getId();

    IclockPerson person = new IclockPerson();
    person.setSiteId(siteId);
    person.setPin("4001");
    person.setName("Native Query Person");
    personId = people.save(person).getId();
  }

  // ------------------------------------------------------- IclockPunchRepository

  @Test
  void findBurstCandidate_findsAPunchOnBothSidesOfTheWindow() {
    // A burst already sitting at T0, spanning 30 seconds.
    IclockPunch burst = punch("4001", T0, T0, T0.plusSeconds(30));

    // Later, but inside the window: the ordinary case.
    assertThat(punches.findBurstCandidate(deviceId, "4001", T0.plusSeconds(60), 120))
        .map(IclockPunch::getId)
        .contains(burst.getId());

    // EARLIER than the burst, also inside the window. This is the direction a one-sided predicate
    // gets wrong, and it is not hypothetical — the fleet buffered ~10 minutes during a DNS outage and
    // flushed out of order, so a punch routinely arrives before the burst it belongs to.
    assertThat(punches.findBurstCandidate(deviceId, "4001", T0.minusSeconds(60), 120))
        .map(IclockPunch::getId)
        .contains(burst.getId());

    // Outside the window on both sides.
    assertThat(punches.findBurstCandidate(deviceId, "4001", T0.plusSeconds(600), 120)).isEmpty();
    assertThat(punches.findBurstCandidate(deviceId, "4001", T0.minusSeconds(600), 120)).isEmpty();

    // A different pin never joins.
    assertThat(punches.findBurstCandidate(deviceId, "4002", T0.plusSeconds(10), 120)).isEmpty();
  }

  @Test
  void findBurstCandidate_returnsTheNEARESTBurstNotJustAnyMatch() {
    IclockPunch near = punch("4001", T0, T0, T0);
    punch("4001", T0.plusSeconds(100), T0.plusSeconds(100), T0.plusSeconds(100));

    // Both are within a 120s window of T0+10; proximity has to decide, or the result depends on the
    // order rows happen to be inserted in.
    assertThat(punches.findBurstCandidate(deviceId, "4001", T0.plusSeconds(10), 120))
        .map(IclockPunch::getId)
        .contains(near.getId());
  }

  @Test
  void existsInterveningPunch_isKeyedOnPersonAndBoundedByArea() {
    // The chain-break punch: same person, SAME area, DIFFERENT device, in between.
    IclockPunch between = punch("4001", T0.plusSeconds(60), T0.plusSeconds(60), T0.plusSeconds(60));
    between.setDeviceId(otherDeviceId);
    punches.save(between);

    assertThat(
            punches.existsInterveningPunch(
                personId, "GATE", deviceId, T0, T0.plus(1, ChronoUnit.HOURS)))
        .isTrue();

    // A CAFETERIA punch must never break a GATE burst.
    assertThat(
            punches.existsInterveningPunch(
                personId, "CAFETERIA", deviceId, T0, T0.plus(1, ChronoUnit.HOURS)))
        .isFalse();

    // Same device is not "intervening" — that is the burst itself.
    assertThat(
            punches.existsInterveningPunch(
                personId, "GATE", otherDeviceId, T0, T0.plus(1, ChronoUnit.HOURS)))
        .isFalse();

    // Outside the bounds.
    assertThat(
            punches.existsInterveningPunch(
                personId, "GATE", deviceId, T0.plus(2, ChronoUnit.HOURS),
                T0.plus(3, ChronoUnit.HOURS)))
        .isFalse();
  }

  @Test
  void existsInterveningPunch_survivesAPersonWithNoLinkedEmployee() {
    // The majority case under the identity-first model: employeeId is NULL. Keying this predicate on
    // a nullable column would make `NULL = NULL` UNKNOWN and silently switch the chain-break test off
    // for almost everybody — no exception, no log line, just bursts quietly merging.
    IclockPunch p = punch("4001", T0.plusSeconds(60), T0.plusSeconds(60), T0.plusSeconds(60));
    p.setDeviceId(otherDeviceId);
    p.setEmployeeId(null);
    p.setCompanyId(null);
    punches.save(p);

    assertThat(p.getEmployeeId()).isNull();
    assertThat(
            punches.existsInterveningPunch(
                personId, "GATE", deviceId, T0, T0.plus(1, ChronoUnit.HOURS)))
        .isTrue();
  }

  // ---------------------------------------------------- IclockRawPunchRepository

  @Test
  void findUnpromoted_returnsOnlyRawPunchesWithNoBurstMembership() {
    punch("4001", T0, T0, T0); // fully promoted: raw + effective punch + membership
    IclockRawPunch orphan = raw("4002", T0.plusSeconds(10), "line-orphan");

    List<IclockRawPunch> found = rawPunches.findUnpromoted(100);

    assertThat(found).extracting(IclockRawPunch::getId).containsExactly(orphan.getId());
    // Read a mapped column back — an entity-returning native query still goes through the driver.
    assertThat(found.get(0).getDevicePin()).isEqualTo("4002");
    assertThat(found.get(0).getReceivedAt()).isNotNull();
  }

  @Test
  void findUnpromotedSince_boundsByTheClaimWindowAndOrdersByDeviceClock() {
    Instant claim = T0;
    raw("4001", claim.minusSeconds(3600), "archive"); // before the claim — archive, must not appear
    IclockRawPunch second = raw("4002", claim.plusSeconds(120), "live-2");
    IclockRawPunch first = raw("4003", claim.plusSeconds(60), "live-1");

    List<IclockRawPunch> found = rawPunches.findUnpromotedSince(claim, 100);

    // Ordered by punchedAtRaw (the DEVICE's clock), not by arrival — a buffered flush has to promote
    // the way it would have live.
    assertThat(found).extracting(IclockRawPunch::getId)
        .containsExactly(first.getId(), second.getId());
    assertThat(found.get(0).getRawLine()).isEqualTo("live-1");
  }

  @Test
  void findUnmappedPinSummary_groupsAndReadsEveryColumnBack() {
    raw("4001", T0, "a");
    raw("4001", T0.plusSeconds(60), "b");
    raw("4002", T0.plusSeconds(120), "c");

    List<Object[]> rows = rawPunches.findUnmappedPinSummary(100);

    assertThat(rows).hasSize(2);
    Object[] top = rows.get(0); // ordered by count desc
    assertThat((String) top[0]).isEqualTo("4001");
    assertThat((String) top[1]).isEqualTo("ZZTESTNQ1");
    assertThat(((Number) top[2]).longValue()).isEqualTo(2L);
    // The two timestamptz aggregates: read, not merely returned. This is the exact position where the
    // driver hands back OffsetDateTime rather than java.sql.Timestamp.
    assertThat(toInstant(top[3])).isNotNull();
    assertThat(toInstant(top[4])).isNotNull();
    assertThat(toInstant(top[3])).isBeforeOrEqualTo(toInstant(top[4]));
  }

  @Test
  void findUnmappedPinSummarySince_splitsLiveFromArchiveAndSurvivesTheDriverTimestampType() {
    Instant claim = T0;
    raw("4001", claim.minusSeconds(7200), "old-1"); // archive only
    raw("4001", claim.minusSeconds(3600), "old-2");
    raw("4002", claim.plusSeconds(60), "live-1"); // punching in the live window
    raw("4002", claim.plusSeconds(120), "live-2");

    List<Object[]> rows = rawPunches.findUnmappedPinSummarySince(siteId, claim, 100);

    assertThat(rows).hasSize(2);

    // Ordered live-first, which is the whole point of the split: the pin punching NOW is somebody at
    // the gate going unattributed, while the archive-only pin needs no action with backfill off.
    Object[] live = rows.get(0);
    assertThat((String) live[0]).isEqualTo("4002");
    assertThat(((Number) live[5]).longValue()).isEqualTo(2L);

    Object[] archive = rows.get(1);
    assertThat((String) archive[0]).isEqualTo("4001");
    assertThat(((Number) archive[2]).longValue()).isEqualTo(2L);
    assertThat(((Number) archive[5]).longValue()).isZero(); // nothing in the live window

    // The regression this test exists for: pgjdbc returns timestamptz as OffsetDateTime under JDBC
    // 4.2, and a cast to java.sql.Timestamp here took out the operator inbox with a 500 in production.
    assertThat(archive[3]).isNotInstanceOf(java.sql.Timestamp.class);
    assertThat(toInstant(archive[3])).isBefore(claim);
    assertThat(toInstant(live[3])).isAfter(claim);
  }

  @Test
  void findUnmappedPinSummarySince_collapsesOnePersonSeenOnSeveralTerminalsIntoOneRow() {
    // One person, walking past both gates — which is what a person does. Orion Towers has an IN
    // terminal and an OUT terminal, so this is the ordinary case, not an edge case.
    rawOn("ZZTESTNQ1", "4001", T0.plusSeconds(60), "gate-in-1");
    rawOn("ZZTESTNQ1", "4001", T0.plusSeconds(120), "gate-in-2");
    rawOn("ZZTESTNQ2", "4001", T0.plusSeconds(180), "gate-out-1");

    List<Object[]> rows = rawPunches.findUnmappedPinSummarySince(siteId, T0, 100);

    // ONE row. Grouping by (devicePin, serialNumber) produced one row per terminal, and since the
    // view carries no serial they were indistinguishable in the console — the same person listed
    // twice with their punches split, inviting the operator to create them twice.
    assertThat(rows).hasSize(1);
    assertThat((String) rows.get(0)[0]).isEqualTo("4001");
    assertThat(((Number) rows.get(0)[2]).longValue()).isEqualTo(3L);
    assertThat(((Number) rows.get(0)[5]).longValue()).isEqualTo(3L);
    // The serial is a diagnostic hint — the terminal seen most recently.
    assertThat((String) rows.get(0)[1]).isEqualTo("ZZTESTNQ2");
  }

  @Test
  void findUnmappedPinSummarySince_excludesAnotherSitesTerminals() {
    IclockSite other = new IclockSite();
    other.setName("Somewhere Else");
    String otherSiteId = sites.save(other).getId();
    IclockDevice foreign = new IclockDevice();
    foreign.setSerialNumber("ZZTESTFOREIGN");
    foreign.setStatus("CLAIMED");
    foreign.setSiteId(otherSiteId);
    foreign.setArea("GATE");
    foreign.setDirection("IN");
    foreign.setClaimedAt(T0.minusSeconds(86_400));
    devices.save(foreign);

    rawOn("ZZTESTNQ1", "4001", T0.plusSeconds(60), "ours");
    rawOn("ZZTESTFOREIGN", "9999", T0.plusSeconds(60), "theirs");

    List<Object[]> rows = rawPunches.findUnmappedPinSummarySince(siteId, T0, 100);

    // The endpoint is /sites/{siteId}/inbox/unmapped. Before the site predicate it answered with the
    // whole fleet, judged against THIS site's claim instant — so another building's unattributed
    // pins appeared in this building's work queue.
    assertThat(rows).hasSize(1);
    assertThat((String) rows.get(0)[0]).isEqualTo("4001");
  }

  // ------------------------------------------------------------------- fixtures

  /** Mirrors {@code IclockInboxService.toInstant} — deliberately type-tolerant about driver types. */
  private static Instant toInstant(Object value) {
    if (value instanceof Instant i) return i;
    if (value instanceof java.time.OffsetDateTime odt) return odt.toInstant();
    if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
    if (value instanceof java.util.Date d) return d.toInstant();
    throw new IllegalStateException("Unexpected timestamp type: " + value);
  }

  private IclockDevice device(String serial, String area, String direction) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setStatus("CLAIMED");
    d.setSiteId(siteId);
    d.setArea(area);
    d.setDirection(direction);
    d.setClaimedAt(T0.minusSeconds(86_400));
    return devices.save(d);
  }

  /**
   * A fully PROMOTED punch: the raw row, the effective punch, and the membership that links them.
   *
   * <p>The membership is not incidental. "Unpromoted" is defined in SQL as "no row in
   * iclock_punch_members", so a fixture that skipped it would leave its own raw punch looking like an
   * orphan and quietly contaminate every inbox assertion in this class.
   */
  private IclockPunch punch(String pin, Instant at, Instant burstFirst, Instant burstLast) {
    IclockRawPunch r = raw(pin, at, "burst-" + at + "-" + burstFirst);
    IclockPunch p = new IclockPunch();
    p.setRawPunchId(r.getId());
    p.setEffectiveRawPunchId(r.getId());
    p.setDeviceId(deviceId);
    p.setSiteId(siteId);
    p.setPersonId(personId);
    p.setDevicePin(pin);
    p.setPunchedAt(at);
    p.setEffectiveAt(at);
    p.setShiftDate(LocalDate.of(2026, 8, 20));
    p.setArea("GATE");
    p.setDirection("IN");
    p.setBurstFirstAt(burstFirst);
    p.setBurstLastAt(burstLast);
    p.setBurstCount(1);
    IclockPunch saved = punches.save(p);
    member(saved.getId(), r.getId());
    return saved;
  }

  private IclockRawPunch raw(String pin, Instant punchedAt, String line) {
    return rawOn("ZZTESTNQ1", pin, punchedAt, line);
  }

  /** A raw punch attributed to a NAMED terminal, for the multi-terminal and cross-site cases. */
  private IclockRawPunch rawOn(String serial, String pin, Instant punchedAt, String line) {
    IclockRawPunch r = new IclockRawPunch();
    r.setDeviceId(devices.findBySerialNumber(serial).map(IclockDevice::getId).orElse(null));
    r.setSerialNumber(serial);
    r.setDevicePin(pin);
    // The device clock arrives as a STRING on the wire, in the terminal's own "yyyy-MM-dd HH:mm:ss"
    // form — and findUnpromotedSince orders by that column directly. Using the real wire format keeps
    // the ordering assertion honest: it sorts lexically the same way it sorts chronologically only
    // because the format is fixed-width and big-endian, which is the property the query relies on.
    r.setPunchedAtRaw(WIRE_FORMAT.format(punchedAt));
    r.setRawLine(line);
    r.setLineNumber(1);
    r.setDedupeKey("nq-" + line + "-" + pin + "-" + punchedAt);
    IclockRawPunch saved = rawPunches.save(r);
    jdbc.update("UPDATE \"iclock_raw_punches\" SET \"receivedAt\" = ? WHERE \"id\" = ?",
        java.sql.Timestamp.from(punchedAt), saved.getId());
    return saved;
  }

  private void member(String punchId, String rawPunchId) {
    IclockPunchMember m = new IclockPunchMember();
    m.setPunchId(punchId);
    m.setRawPunchId(rawPunchId);
    members.save(m);
  }
}
