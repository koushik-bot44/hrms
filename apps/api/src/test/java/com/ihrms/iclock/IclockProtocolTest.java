package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockRequestLogRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end protocol behaviour against a running server and a real Postgres.
 *
 * <p>The assertions that matter most are the negative ones: a device rejects any non-{@code
 * text/plain} reply and retries forever, so an accidental JSON body on ANY {@code /iclock} path is a
 * hard defect. The app has four independent JSON emitters (the security entry point, the no-handler
 * 404, the global exception advice, and the rate limiter), and these tests prove none can reach a
 * terminal.
 *
 * <p>Gated on {@code IHRMS_TEST_DB} like every other {@code @SpringBootTest} here. It is also the
 * earliest signal for a migration-versus-entity mismatch, which would otherwise abort context startup
 * app-wide in production.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"app.iclock.enabled=true"})
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockProtocolTest {

  private static final String SN = "TESTSN0001";

  @Autowired private TestRestTemplate http;
  @Autowired private IclockRawPunchRepository punches;
  @Autowired private IclockRequestLogRepository logs;
  @Autowired private IclockDeviceRepository devices;
  @Autowired private JdbcTemplate jdbc;

  // ------------------------------------------------------------- both dialects

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/cdata", "/iclock/cdata.aspx"})
  void handshakeReturnsPlainTextOptionsEnablingRealtimePush(String path) {
    ResponseEntity<String> res =
        http.getForEntity(path + "?SN=" + SN + "&options=all&pushver=2.4.1", String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody())
        .contains("Realtime=1")
        .contains("GET OPTION FROM: " + SN)
        .contains("ATTLOGStamp=");
  }

  @Test
  void theAspxHandshakeStampsLastHandshakeAtAndFirmwareInfo() {
    // Under P0 this fell to the catch-all, so lastHandshakeAt stayed NULL and firmwareInfo empty even
    // though the device had handshaken. That is the regression this guards.
    String serial = "HSHAKE0001";
    http.getForEntity(
        "/iclock/cdata.aspx?SN=" + serial + "&options=all&pushver=2.4.1&DeviceType=att",
        String.class);

    Optional<IclockDevice> device = devices.findBySerialNumber(serial);
    assertThat(device).isPresent();
    assertThat(device.get().getLastHandshakeAt()).isNotNull();
    assertThat(device.get().getFirmwareInfo()).contains("pushver=2.4.1").contains("DeviceType=att");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/getrequest", "/iclock/getrequest.aspx"})
  void commandPollReturnsOkWhenTheQueueIsEmpty(String path) {
    ResponseEntity<String> res = http.getForEntity(path + "?SN=" + SN, String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK");
  }

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/cdata", "/iclock/cdata.aspx"})
  void attlogPushIsAcknowledgedWithTheLineCountAndStoresEveryLine(String path) {
    String serial = "ATT" + path.hashCode();
    String body =
        serial + "01\t2026-08-25 09:15:00\t255\t15\t0\n" + serial + "02\t2026-08-25 09:16:30\t255\t15\t0\n";

    ResponseEntity<String> res =
        post(path + "?SN=" + serial + "&table=ATTLOG&Stamp=9999", body, null);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK: 2");
    assertThat(punches.countBySerialNumber(serial)).isEqualTo(2);
  }

  // ------------------------------------------------------------------ dedupe

  @Test
  void reUploadingTheSameBatchDoesNotDoubleCount() {
    // The device re-pushes its whole backlog after any non-OK reply, and the one-off replay re-reads
    // captured bodies. Without content dedupe either would duplicate history.
    String serial = "DEDUPE0001";
    String body =
        "9001\t2026-08-25 11:00:00\t255\t15\t0\n" + "9002\t2026-08-25 11:00:30\t255\t15\t0\n";
    String url = "/iclock/cdata.aspx?SN=" + serial + "&table=ATTLOG&Stamp=9999";

    ResponseEntity<String> first = post(url, body, null);
    ResponseEntity<String> second = post(url, body, null);

    // Both are acknowledged for every line — a deduped re-upload must never look like a failure, or
    // the device keeps retrying a batch we already hold.
    assertThat(first.getBody()).isEqualTo("OK: 2");
    assertThat(second.getBody()).isEqualTo("OK: 2");
    assertThat(punches.countBySerialNumber(serial)).isEqualTo(2);
  }

  @Test
  void theJavaDedupeKeyMatchesThePostgresBackfillExpression() {
    // V42 backfilled existing rows with md5(serialNumber || E'\n' || rawLine) in SQL, while live
    // ingest computes the key in Java. If these ever drift, old and new rows stop colliding and the
    // entire captured history re-imports on the next replay.
    String serial = "PARITY0001";
    String rawLine = "18292\t2026-07-16 01:59:44\t255\t15\t0\t0\t0\t0\t0\t0\t";

    String fromPostgres =
        jdbc.queryForObject(
            "SELECT md5(? || E'\\n' || ?)", String.class, serial, rawLine);

    assertThat(IclockDedupe.key(serial, rawLine)).isEqualTo(fromPostgres);
  }

  // ------------------------------------------------------------ other tables

  @ParameterizedTest
  @ValueSource(strings = {"OPERLOG", "BIODATA"})
  void otherRegistriesAreAcceptedWithoutStructuredParsing(String table) {
    ResponseEntity<String> res =
        post("/iclock/cdata.aspx?SN=" + SN + "&table=" + table + "&OpStamp=9999", "OPLOG\t1\t2\n", null);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK");
  }

  @Test
  void formUrlEncodedPushStillHasItsRawBodyCaptured() {
    // If anything touched getParameter() on this content type, Tomcat would consume the body and the
    // punches would silently vanish. The cached-body wrapper is what prevents that.
    String serial = "FORMENC001";
    String body = "301\t2026-08-25 10:00:00\t255\t15\t0\n";

    ResponseEntity<String> res =
        post(
            "/iclock/cdata.aspx?SN=" + serial + "&table=ATTLOG",
            body,
            MediaType.APPLICATION_FORM_URLENCODED);

    assertThat(res.getBody()).isEqualTo("OK: 1");
    assertThat(punches.findBySerialNumberOrderByReceivedAtDesc(serial))
        .anyMatch(p -> "301".equals(p.getDevicePin()));
  }

  // -------------------------------------------------------------- containment

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/fdata", "/iclock/fdata.aspx", "/iclock/devicecmd.aspx"})
  void anUnimplementedIclockPathReturnsPlainTextNotAJsonFourOhFour(String path) {
    // throw-exception-if-no-handler-found is on, so without the catch-all this would be a JSON 404
    // the device retries forever.
    ResponseEntity<String> res = http.getForEntity(path + "?SN=" + SN, String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK").doesNotContain("{");
  }

  @Test
  void unknownMethodsOnAKnownPathAlsoStayPlainText() {
    ResponseEntity<String> res =
        http.exchange("/iclock/cdata.aspx?SN=" + SN, HttpMethod.PUT, HttpEntity.EMPTY, String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
  }

  @Test
  void theBareIclockPathIsHandledAndLogged() {
    // "/iclock" has no trailing slash, so a naive startsWith("/iclock/") guard would skip logging it
    // while the security matcher and the MVC pattern both still match — a silent blind spot.
    ResponseEntity<String> res = http.getForEntity("/iclock", String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(logs.findAll()).anyMatch(l -> "/iclock".equals(l.getPath()));
  }

  // ------------------------------------------------------------------ capture

  @Test
  void everyRequestIsCapturedVerbatimForDialectForensics() {
    http.getForEntity("/iclock/getrequest.aspx?SN=" + SN + "&probe=1", String.class);

    assertThat(logs.findBySerialNumberOrderByReceivedAtDesc(SN))
        .isNotEmpty()
        .anyMatch(l -> l.getQueryString() != null && l.getQueryString().contains("probe=1"));
  }

  @Test
  void sensitiveHeadersAreRedactedBeforeStorage() {
    // The capture filter runs ahead of Spring Security, so it sees tokens that were never meant for
    // it. Persisting a live JWT in an unencrypted, unretained table would be a real leak.
    HttpHeaders headers = new HttpHeaders();
    headers.set(HttpHeaders.AUTHORIZATION, "Bearer super-secret-token-value");
    http.exchange(
        "/iclock/getrequest.aspx?SN=" + SN + "&redact=1",
        HttpMethod.GET,
        new HttpEntity<>(headers),
        String.class);

    assertThat(logs.findBySerialNumberOrderByReceivedAtDesc(SN))
        .filteredOn(l -> l.getQueryString() != null && l.getQueryString().contains("redact=1"))
        .isNotEmpty()
        .allSatisfy(
            l -> {
              assertThat(l.getHeaders()).doesNotContain("super-secret-token-value");
              assertThat(l.getHeaders()).contains("[REDACTED]");
            });
  }

  /**
   * Asserts the header is exactly {@code text/plain} with no {@code charset} parameter. The captured
   * firmware sends a wildcard Accept, but the exact header is pinned rather than prefix-checked — a
   * {@code startsWith} assertion would pass either way and prove nothing.
   */
  private static void assertExactlyTextPlain(ResponseEntity<String> res) {
    assertThat(res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).isEqualTo("text/plain");
  }

  private ResponseEntity<String> post(String url, String body, MediaType contentType) {
    HttpHeaders headers = new HttpHeaders();
    if (contentType != null) {
      headers.setContentType(contentType);
    }
    return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
  }
}
