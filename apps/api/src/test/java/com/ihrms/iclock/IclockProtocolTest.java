package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockRequestLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end protocol behaviour against a running server.
 *
 * <p>The assertions that matter most are the negative ones: a device rejects any non-{@code
 * text/plain} reply and retries forever, so an accidental JSON body on ANY {@code /iclock} path is a
 * hard defect. The app has four independent JSON emitters (the security entry point, the
 * no-handler 404, the global exception advice, and the rate limiter), and these tests exist to prove
 * none of them can reach a terminal.
 *
 * <p>Gated on {@code IHRMS_TEST_DB} like every other {@code @SpringBootTest} here, since Flyway and
 * Hibernate schema validation need a real Postgres. It also serves as the earliest signal for a
 * V41-versus-entity mismatch, which would otherwise abort context startup app-wide in production.
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

  @Test
  void handshakeReturnsPlainTextOptionsEnablingRealtimePush() {
    ResponseEntity<String> res =
        http.getForEntity("/iclock/cdata?SN=" + SN + "&options=all", String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).contains("Realtime=1").contains("GET OPTION FROM: " + SN);
  }

  @Test
  void commandPollReturnsOkWhenTheQueueIsEmpty() {
    ResponseEntity<String> res = http.getForEntity("/iclock/getrequest?SN=" + SN, String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK");
  }

  @Test
  void attlogPushIsAcknowledgedWithTheLineCountAndStoresEveryLine() {
    String body =
        "201\t2026-08-25 09:15:00\t0\t1\t0\n" + "202\t2026-08-25 09:16:00\t1\t1\t0\n";

    ResponseEntity<String> res = post("/iclock/cdata?SN=" + SN + "&table=ATTLOG", body, null);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    // The count is what lets the terminal advance its cursor and stop re-sending.
    assertThat(res.getBody()).isEqualTo("OK: 2");
    assertThat(punches.countBySerialNumber(SN)).isGreaterThanOrEqualTo(2);
  }

  @Test
  void formUrlEncodedPushStillHasItsRawBodyCaptured() {
    // If anything touched getParameter() on this content type, Tomcat would consume the body and the
    // punches would silently vanish. The cached-body wrapper is what prevents that.
    String body = "301\t2026-08-25 10:00:00\t0\t1\t0\n";

    ResponseEntity<String> res =
        post("/iclock/cdata?SN=" + SN + "&table=ATTLOG", body, MediaType.APPLICATION_FORM_URLENCODED);

    assertThat(res.getBody()).isEqualTo("OK: 1");
    assertThat(punches.findBySerialNumberOrderByReceivedAtDesc(SN))
        .anyMatch(p -> "301".equals(p.getDevicePin()));
  }

  @Test
  void nonAttlogTablesAreAcceptedWithoutStructuredParsing() {
    ResponseEntity<String> res =
        post("/iclock/cdata?SN=" + SN + "&table=OPERLOG", "OPLOG\t1\t2\n", null);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK");
  }

  @Test
  void anUnimplementedIclockPathReturnsPlainTextNotAJsonFourOhFour() {
    // The single most likely way this integration breaks: throw-exception-if-no-handler-found is on,
    // so without the catch-all mapping this would be a JSON 404 the device retries forever.
    ResponseEntity<String> res = http.getForEntity("/iclock/fdata?SN=" + SN, String.class);

    assertThat(res.getStatusCode().value()).isEqualTo(200);
    assertExactlyTextPlain(res);
    assertThat(res.getBody()).isEqualTo("OK");
    assertThat(res.getBody()).doesNotContain("{");
  }

  @Test
  void unknownMethodsOnAKnownPathAlsoStayPlainText() {
    ResponseEntity<String> res =
        http.exchange("/iclock/cdata?SN=" + SN, HttpMethod.PUT, HttpEntity.EMPTY, String.class);

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

  @Test
  void everyRequestIsCapturedVerbatimForDialectForensics() {
    http.getForEntity("/iclock/getrequest?SN=" + SN + "&probe=1", String.class);

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
        "/iclock/getrequest?SN=" + SN + "&redact=1",
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
   * Asserts the header is exactly {@code text/plain} with no {@code charset} parameter. Whether this
   * firmware tolerates a charset suffix is an open question, so the wire format is pinned rather than
   * merely prefix-checked — a {@code startsWith} assertion would pass either way and prove nothing.
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
