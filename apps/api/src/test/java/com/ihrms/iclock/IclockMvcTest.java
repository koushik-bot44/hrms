package com.ihrms.iclock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ihrms.audit.AuditInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Protocol behaviour at the MVC layer, with no database.
 *
 * <p>This exists because the full {@code @SpringBootTest} is gated on {@code IHRMS_TEST_DB} and is
 * therefore skipped on any machine (and any CI run) without Postgres — leaving the single most
 * important guarantee of this module unverified. These assertions run everywhere.
 *
 * <p>What it proves: the request mappings resolve without ambiguity (a duplicate-mapping error would
 * fail context startup here), every reply is exactly {@code text/plain} with no charset parameter,
 * and — most importantly — an unimplemented {@code /iclock} path is answered by the catch-all rather
 * than becoming a JSON 404. Filters are disabled so the controller is tested in isolation.
 *
 * <p>Query parameters are written into the URL rather than passed via {@code .param()} on purpose:
 * the controller reads the RAW query string and never touches {@code getParameter()}, because on a
 * form-urlencoded POST the parameter map would consume the ATTLOG body. {@code .param()} populates
 * only the map and leaves {@code getQueryString()} null, which no real device request ever does.
 */
@WebMvcTest(controllers = IclockController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(IclockMvcTest.Config.class)
class IclockMvcTest {

  /**
   * MUST be {@code @TestConfiguration}, not {@code @Configuration}: a plain nested
   * {@code @Configuration} inside a test class is picked up as the PRIMARY configuration source,
   * which stops the slice from ever locating {@code ApiApplication} — the component scan never runs
   * and no controller is registered, so every request 404s in a way that looks like a routing bug.
   */
  @TestConfiguration(proxyBeanMethods = false)
  static class Config {
    @Bean
    IclockProperties iclockProperties() {
      return new IclockProperties(true, 262_144, 600, "count", IclockProperties.Options.defaults());
    }
  }

  @Autowired private MockMvc mvc;

  @MockBean private IclockService service;

  /** Required by WebConfig, which registers it for every path. Not under test here. */
  @MockBean private AuditInterceptor auditInterceptor;

  /**
   * A {@code @WebMvcTest} slice instantiates Filter beans, so {@link IclockRequestLogFilter} is
   * constructed even though {@code addFilters = false} stops it from running — and it needs this
   * collaborator to exist.
   */
  @MockBean private IclockRateLimiter rateLimiter;

  @BeforeEach
  void setUp() throws Exception {
    // A Mockito mock returns false for a boolean, which would abort every request before the handler.
    when(auditInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    when(service.persistenceEnabled()).thenReturn(true);
  }

  @Test
  void handshakeReturnsBareTextPlainOptionsWithRealtimeEnabled() throws Exception {
    mvc.perform(get("/iclock/cdata?SN=ABC123&options=all"))
        .andExpect(status().isOk())
        // Exactly "text/plain" — not "text/plain;charset=UTF-8". Whether this firmware tolerates a
        // charset parameter is unknown, so the wire format is pinned.
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Realtime=1")));
  }

  @Test
  void commandPollReturnsOk() throws Exception {
    mvc.perform(get("/iclock/getrequest?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  @Test
  void attlogPushIsAcknowledgedWithTheLineCount() throws Exception {
    when(service.ingestAttlog(anyString(), nullable(String.class), nullable(String.class)))
        .thenReturn(2);

    mvc.perform(
            post("/iclock/cdata?SN=ABC123&table=ATTLOG")
                .content("201\t2026-08-25 09:15:00\t0\t1\t0\n202\t2026-08-25 09:16:00\t1\t1\t0\n"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK: 2"));
  }

  @Test
  void otherTablesAreAcknowledgedWithoutParsing() throws Exception {
    mvc.perform(post("/iclock/cdata?SN=ABC123&table=OPERLOG").content("x"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  @Test
  void anUnimplementedIclockPathIsCaughtRatherThanBecomingAJsonFourOhFour() throws Exception {
    // The highest-value assertion in this class. Real firmware probes /iclock/devicecmd, /fdata,
    // /rtdata, /ping and others depending on model; each would otherwise be a JSON 404 the device
    // rejects and retries forever.
    mvc.perform(get("/iclock/fdata?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  @Test
  void anUnexpectedMethodOnAKnownPathAlsoFallsToTheCatchAll() throws Exception {
    mvc.perform(put("/iclock/cdata?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  @Test
  void aHandlerFailureIsStillAnsweredInPlainTextNotTheJsonErrorEnvelope() throws Exception {
    // Proves the controller-local @ExceptionHandler beats the global @RestControllerAdvice.
    when(service.ingestAttlog(anyString(), nullable(String.class), nullable(String.class)))
        .thenThrow(new RuntimeException("boom"));

    mvc.perform(post("/iclock/cdata?SN=ABC123&table=ATTLOG").content("x\ty"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  @Test
  void aPushIsRefusedWhileDisabledSoTheDeviceKeepsItsBatch() throws Exception {
    when(service.persistenceEnabled()).thenReturn(false);

    // Answering "OK" here would be silent, permanent data loss: the terminal would treat the batch as
    // delivered and advance its cursor, even though nothing was stored. It must be told to retry —
    // in text/plain, never JSON.
    mvc.perform(post("/iclock/cdata?SN=ABC123&table=ATTLOG").content("x\ty"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"));

    org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
        .ingestAttlog(anyString(), nullable(String.class), nullable(String.class));
    org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
        .touch(nullable(String.class), anyBoolean(), nullable(String.class));
  }

  @Test
  void aWriteToAnUnknownPathIsAlsoRefusedWhileDisabled() throws Exception {
    when(service.persistenceEnabled()).thenReturn(false);

    // An unmapped write may equally be carrying data (fdata, rtdata, an unmapped table variant).
    mvc.perform(post("/iclock/fdata?SN=ABC123").content("payload"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"));
  }

  @Test
  void readsAreStillAnsweredNormallyWhileDisabledSoTheDeviceIsNotErrorLooped() throws Exception {
    when(service.persistenceEnabled()).thenReturn(false);

    mvc.perform(get("/iclock/getrequest?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));

    mvc.perform(get("/iclock/cdata?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"));

    org.mockito.Mockito.verify(service, org.mockito.Mockito.never())
        .touch(nullable(String.class), anyBoolean(), nullable(String.class));
  }

  @Test
  void theHandshakeRecordsTheDeviceAsAHandshakeNotAPoll() throws Exception {
    mvc.perform(get("/iclock/cdata?SN=ABC123")).andExpect(status().isOk());

    org.mockito.Mockito.verify(service).touch(eq("ABC123"), eq(true), nullable(String.class));
  }
}
