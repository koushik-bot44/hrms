package com.ihrms.iclock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Protocol behaviour at the MVC layer, with no database.
 *
 * <p>Every endpoint is exercised in BOTH spellings — bare and {@code .aspx}. The deployed firmware
 * (ZAM180 / pushver 2.4.1) uses {@code .aspx}; P0 mapped only the bare form, so real pushes fell to
 * the catch-all and 16,970 lines went unparsed. These parameterized tests are the regression guard
 * against that reappearing.
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
      return new IclockProperties(
          true, 262_144, 600, "count", "full", IclockProperties.Options.defaults());
    }
  }

  @Autowired private MockMvc mvc;

  @MockBean private IclockService service;

  /** Required by WebConfig, which registers it for every path. Not under test here. */
  @MockBean private AuditInterceptor auditInterceptor;

  /**
   * A {@code @WebMvcTest} slice instantiates Filter beans, so {@link IclockRequestLogFilter} is
   * constructed even though {@code addFilters = false} stops it from running.
   */
  @MockBean private IclockRateLimiter rateLimiter;

  @BeforeEach
  void setUp() throws Exception {
    // A Mockito mock returns false for a boolean, which would abort every request before the handler.
    when(auditInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    when(service.persistenceEnabled()).thenReturn(true);
  }

  private void stubIngest(int lines, int inserted, int dupes) {
    when(service.ingestAttlog(
            nullable(String.class), nullable(String.class), nullable(String.class),
            nullable(String.class)))
        .thenReturn(new IclockService.IngestResult(lines, inserted, dupes));
  }

  // ---------------------------------------------------------------- handshake

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/cdata", "/iclock/cdata.aspx"})
  void handshakeReturnsBareTextPlainOptionsWithRealtimeEnabled(String path) throws Exception {
    mvc.perform(get(path + "?SN=ABC123&options=all&pushver=2.4.1&DeviceType=att"))
        .andExpect(status().isOk())
        // Exactly "text/plain" — not "text/plain;charset=UTF-8".
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Realtime=1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("ATTLOGStamp=")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/cdata", "/iclock/cdata.aspx"})
  void handshakeRecordsTheDeviceAsAHandshakeAndKeepsTheQueryAsFirmwareInfo(String path)
      throws Exception {
    String query = "SN=ABC123&options=all&pushver=2.4.1&DeviceType=att";

    mvc.perform(get(path + "?" + query)).andExpect(status().isOk());

    // handshake=true is what sets lastHandshakeAt; the query string is the firmware evidence.
    verify(service).touch(eq("ABC123"), eq(true), eq(query), nullable(String.class), nullable(String.class));
  }

  // ------------------------------------------------------------- command poll

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/getrequest", "/iclock/getrequest.aspx"})
  void commandPollReturnsOk(String path) throws Exception {
    mvc.perform(get(path + "?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  // -------------------------------------------------------------------- push

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/cdata", "/iclock/cdata.aspx"})
  void attlogPushIsAcknowledgedWithTheLineCount(String path) throws Exception {
    stubIngest(2, 2, 0);

    mvc.perform(
            post(path + "?SN=ABC123&table=ATTLOG&Stamp=9999")
                .content("201\t2026-08-25 09:15:00\t255\t15\t0\n202\t2026-08-25 09:16:00\t255\t15\t0\n"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK: 2"));
  }

  @Test
  void theAckCountsLinesReceivedNotRowsInserted() throws Exception {
    // A fully-deduped re-upload must still be acknowledged for every line, or the device re-sends a
    // batch we already hold.
    stubIngest(5, 0, 5);

    mvc.perform(post("/iclock/cdata.aspx?SN=ABC123&table=ATTLOG&Stamp=9999").content("x\ty"))
        .andExpect(status().isOk())
        .andExpect(content().string("OK: 5"));
  }

  @Test
  void theStampParameterIsPassedThroughToIngest() throws Exception {
    stubIngest(1, 1, 0);

    mvc.perform(post("/iclock/cdata.aspx?SN=ABC123&table=ATTLOG&Stamp=9999").content("x\ty"))
        .andExpect(status().isOk());

    verify(service)
        .ingestAttlog(eq("ABC123"), nullable(String.class), nullable(String.class), eq("9999"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"OPERLOG", "BIODATA"})
  void otherRegistriesAreAcknowledgedWithoutParsingButStillRecordOpStamp(String table)
      throws Exception {
    mvc.perform(post("/iclock/cdata.aspx?SN=ABC123&table=" + table + "&OpStamp=9999").content("x"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));

    // OpStamp, not Stamp — these registries use the other parameter name.
    verify(service)
        .touch(eq("ABC123"), eq(false), nullable(String.class), nullable(String.class), eq("9999"));
    verify(service, never())
        .ingestAttlog(
            nullable(String.class), nullable(String.class), nullable(String.class),
            nullable(String.class));
  }

  // --------------------------------------------------------------- catch-all

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/fdata", "/iclock/fdata.aspx", "/iclock/devicecmd.aspx", "/iclock/rtdata"})
  void anUnimplementedIclockPathReturnsPlainTextNotAJsonFourOhFour(String path) throws Exception {
    mvc.perform(get(path + "?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  @Test
  void anUnexpectedMethodOnAKnownPathAlsoFallsToTheCatchAll() throws Exception {
    mvc.perform(put("/iclock/cdata.aspx?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  // ------------------------------------------------------------- error paths

  @Test
  void aHandlerFailureIsStillAnsweredInPlainTextNotTheJsonErrorEnvelope() throws Exception {
    // Proves the controller-local @ExceptionHandler beats the global @RestControllerAdvice.
    when(service.ingestAttlog(
            nullable(String.class), nullable(String.class), nullable(String.class),
            nullable(String.class)))
        .thenThrow(new RuntimeException("boom"));

    mvc.perform(post("/iclock/cdata.aspx?SN=ABC123&table=ATTLOG").content("x\ty"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
        .andExpect(content().string("OK"));
  }

  // ----------------------------------------------------------- disabled mode

  @ParameterizedTest
  @ValueSource(strings = {"/iclock/cdata", "/iclock/cdata.aspx"})
  void aPushIsRefusedWhileDisabledSoTheDeviceKeepsItsBatch(String path) throws Exception {
    when(service.persistenceEnabled()).thenReturn(false);

    // Answering "OK" here would be silent, permanent data loss: the terminal would treat the batch as
    // delivered and advance its pointer even though nothing was stored.
    mvc.perform(post(path + "?SN=ABC123&table=ATTLOG").content("x\ty"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"));

    verify(service, never())
        .ingestAttlog(
            nullable(String.class), nullable(String.class), nullable(String.class),
            nullable(String.class));
  }

  @Test
  void aWriteToAnUnknownPathIsAlsoRefusedWhileDisabled() throws Exception {
    when(service.persistenceEnabled()).thenReturn(false);

    mvc.perform(post("/iclock/fdata.aspx?SN=ABC123").content("payload"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"));
  }

  @Test
  void readsAreStillAnsweredNormallyWhileDisabledSoTheDeviceIsNotErrorLooped() throws Exception {
    when(service.persistenceEnabled()).thenReturn(false);

    mvc.perform(get("/iclock/getrequest.aspx?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(content().string("OK"));
    mvc.perform(get("/iclock/cdata.aspx?SN=ABC123"))
        .andExpect(status().isOk())
        .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"));

    verify(service, never())
        .touch(
            nullable(String.class), anyBoolean(), nullable(String.class), nullable(String.class),
            nullable(String.class));
  }
}
