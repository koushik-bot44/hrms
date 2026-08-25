package com.ihrms.iclock;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The eSSL/ZKTeco ADMS ("iClock" push") endpoints — Phase 0: handshake, command poll, and raw ATTLOG
 * ingest. No employee mapping, no attendance derivation, no remote commands.
 *
 * <p><b>Everything in this class is one class on purpose.</b> A controller-local
 * {@code @ExceptionHandler} only covers handler methods declared in its own controller, and the
 * catch-all mapping is what stops an unimplemented {@code /iclock} path reaching the container's
 * no-handler machinery ({@code throw-exception-if-no-handler-found: true}) and coming back as JSON.
 * Splitting either of them into another class silently reopens the JSON leak.
 *
 * <p><b>No class-level {@code @RequestMapping}</b> — it would compose into {@code /iclock/iclock/**}.
 *
 * <p>Responses are {@code ResponseEntity<byte[]>} with an explicit {@code text/plain} content type.
 * Bytes rather than String so the {@code StringHttpMessageConverter} cannot append a {@code charset}
 * parameter to the header; whether this firmware tolerates one is an open question, and this makes
 * the wire format deterministic either way.
 */
@RestController
public class IclockController {

  private static final Logger log = LoggerFactory.getLogger(IclockController.class);

  private final IclockService service;
  private final IclockProperties props;

  public IclockController(IclockService service, IclockProperties props) {
    this.service = service;
    this.props = props;
  }

  /**
   * Handshake. The device fetches its configuration here; {@code Realtime=1} in the reply is what
   * switches it from scheduled batch upload to live push.
   */
  @GetMapping("/iclock/cdata")
  public ResponseEntity<byte[]> handshake(HttpServletRequest request) {
    String serial = IclockQuery.param(request.getQueryString(), "SN");
    if (service.persistenceEnabled()) {
      // The full query string is kept as firmware info: it carries pushver/options and is our best
      // early evidence of which dialect this terminal speaks.
      service.touch(serial, true, request.getQueryString());
    }
    String block =
        IclockOptions.block(serial, Instant.now().getEpochSecond(), props.options());
    return text(block);
  }

  /**
   * Command poll, every few seconds. P0 issues no commands, so the queue is always empty and the
   * device is told so with a bare "OK".
   */
  @GetMapping("/iclock/getrequest")
  public ResponseEntity<byte[]> getrequest(HttpServletRequest request) {
    if (service.persistenceEnabled()) {
      service.touch(IclockQuery.param(request.getQueryString(), "SN"), false, null);
    }
    return text("OK");
  }

  /**
   * Push. {@code table=ATTLOG} is parsed into one row per line; every other table (OPERLOG and
   * whatever else this firmware sends) is acknowledged without structured parsing — the raw capture
   * row already holds it verbatim, which is all P0 needs.
   *
   * <p>The acknowledgement is what lets the terminal advance its cursor and stop re-sending, so it
   * is returned even when the parse or the insert failed.
   */
  @PostMapping("/iclock/cdata")
  public ResponseEntity<byte[]> push(HttpServletRequest request) {
    String query = request.getQueryString();
    String serial = IclockQuery.param(query, "SN");
    String table = IclockQuery.param(query, "table");
    String body = rawBody(request);

    if (!service.persistenceEnabled()) {
      // NEVER acknowledge data we did not store. "OK" would let the terminal advance its cursor and
      // discard the batch — silent, permanent data loss if a device is ever pointed at an instance
      // where ingest is switched off. A non-OK reply makes it retain and retry instead.
      return unavailable();
    }
    if (table == null || !table.equalsIgnoreCase("ATTLOG")) {
      service.touch(serial, false, null);
      return text("OK");
    }
    String logId = (String) request.getAttribute(IclockRequestLogFilter.ATTR_LOG_ID);
    int lines = service.ingestAttlog(serial, body, logId);
    return text(props.ackWithCount() ? "OK: " + lines : "OK");
  }

  /**
   * Catch-all for any other {@code /iclock} path or method — {@code devicecmd}, {@code fdata},
   * {@code ping}, whatever this firmware probes. MANDATORY, not defensive: without it those requests
   * become a {@code NoHandlerFoundException} and a JSON 404 the device would reject and retry
   * forever. The capture filter has already recorded the request verbatim, which is how we learn
   * which paths to implement properly in Phase 1.
   */
  @RequestMapping("/iclock/**")
  public ResponseEntity<byte[]> catchAll(HttpServletRequest request) {
    if (!service.persistenceEnabled()) {
      // A write method on an unknown path may well be carrying data (fdata, rtdata, a table variant
      // we have not mapped). Same rule as above: do not acknowledge what we did not store. Reads stay
      // "OK" so a device is not error-looped on harmless polling.
      return isWrite(request) ? unavailable() : text("OK");
    }
    service.touch(IclockQuery.param(request.getQueryString(), "SN"), false, null);
    return text("OK");
  }

  private static boolean isWrite(HttpServletRequest request) {
    String m = request.getMethod();
    return "POST".equalsIgnoreCase(m) || "PUT".equalsIgnoreCase(m) || "PATCH".equalsIgnoreCase(m);
  }

  /**
   * Last line of defence inside the containment zone. Anything thrown by a handler above is answered
   * "OK" in {@code text/plain} rather than escaping to the global {@code @RestControllerAdvice},
   * whose JSON envelope would break the device.
   *
   * <p>Note the trade-off this makes: because every failure still returns 200, a completely broken
   * ingest is indistinguishable from a healthy one from outside the process. The signal to watch is
   * this log line and the row counts in {@code iclock_request_logs} / {@code iclock_raw_punches}.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> handleAnything(HttpServletRequest request, Exception e) {
    log.warn("iclock: handler error on {} {}", request.getMethod(), request.getRequestURI(), e);
    return text("OK");
  }

  /**
   * The body the capture filter already read. Falls back to reading the stream only if the filter
   * did not run, which should be impossible for a mapped {@code /iclock} path.
   */
  private String rawBody(HttpServletRequest request) {
    Object cached = request.getAttribute(IclockRequestLogFilter.ATTR_BODY);
    if (cached instanceof String s) {
      return s;
    }
    try (InputStream in = request.getInputStream()) {
      return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
    } catch (IOException e) {
      log.warn("iclock: could not read body in handler", e);
      return "";
    }
  }

  private static ResponseEntity<byte[]> text(String payload) {
    return ResponseEntity.status(HttpStatus.OK)
        .contentType(MediaType.TEXT_PLAIN)
        .body(payload.getBytes(StandardCharsets.ISO_8859_1));
  }

  /**
   * A non-OK reply that is still {@code text/plain}. Used only when the module is switched off: the
   * device must keep its batch and retry rather than treat an unstored push as delivered.
   */
  private static ResponseEntity<byte[]> unavailable() {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .contentType(MediaType.TEXT_PLAIN)
        .body("ingest disabled".getBytes(StandardCharsets.ISO_8859_1));
  }
}
