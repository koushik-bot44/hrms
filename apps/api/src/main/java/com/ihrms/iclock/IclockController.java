package com.ihrms.iclock;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
 * The eSSL/ZKTeco ADMS ("iClock" push") endpoints — Phase 0/0.1: handshake, command poll, and raw
 * ATTLOG ingest. No employee mapping, no attendance derivation, no remote commands.
 *
 * <p><b>Both path spellings are mapped.</b> The first real capture showed this firmware
 * ({@code ZAM180-NF50VD-4.0.12-CR-1545-01}, pushver 2.4.1) calls {@code /iclock/cdata.aspx} and
 * {@code /iclock/getrequest.aspx}. Under P0 those fell through to the catch-all, so 16,970 ATTLOG
 * lines were captured raw but never parsed and the handshake never delivered its options block.
 * Other firmware uses the bare form, so both are accepted rather than swapped.
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
 * parameter; the captured device sends a wildcard Accept header, so negotiation is not a factor
 * either way.
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
   *
   * <p>The full query string is stored as {@code firmwareInfo} — it carries {@code pushver},
   * {@code DeviceType} and {@code language}, which is our best record of what this terminal is.
   */
  @GetMapping({"/iclock/cdata", "/iclock/cdata.aspx"})
  public ResponseEntity<byte[]> handshake(HttpServletRequest request) {
    String query = request.getQueryString();
    String serial = IclockQuery.param(query, "SN");
    IclockStore.DeviceView device = null;
    if (service.persistenceEnabled()) {
      device = service.touch(serial, true, query, null, null);
    }
    String block =
        IclockOptions.block(
            serial,
            device == null ? null : device.attlogStamp(),
            device == null ? null : device.opStamp(),
            props.options());
    return text(block);
  }

  /**
   * Command poll, every {@code Delay} seconds. P0 issues no commands, so the queue is always empty
   * and the device is told so with a bare "OK".
   */
  @GetMapping({"/iclock/getrequest", "/iclock/getrequest.aspx"})
  public ResponseEntity<byte[]> getrequest(HttpServletRequest request) {
    if (service.persistenceEnabled()) {
      String query = request.getQueryString();
      // Some polls carry &INFO=<firmware,counters,...>; keep it, it is free dialect evidence.
      String info = IclockQuery.param(query, "INFO");
      service.touch(IclockQuery.param(query, "SN"), false, info == null ? null : query, null, null);
    }
    return text("OK");
  }

  /**
   * Push. {@code table=ATTLOG} is parsed into one row per line; OPERLOG, BIODATA and anything else
   * this firmware sends are acknowledged without structured parsing — the raw capture row already
   * holds them verbatim, which is all P0 needs.
   *
   * <p>The acknowledgement is what lets the terminal advance its own pointer and stop re-sending, so
   * it is returned even when the parse or the insert failed.
   */
  @PostMapping({"/iclock/cdata", "/iclock/cdata.aspx"})
  public ResponseEntity<byte[]> push(HttpServletRequest request) {
    String query = request.getQueryString();
    String serial = IclockQuery.param(query, "SN");
    String table = IclockQuery.param(query, "table");
    // ATTLOG uses Stamp; OPERLOG and BIODATA use OpStamp. Accept either name on either path rather
    // than assuming which registry a given firmware pairs with which parameter.
    String stamp = IclockQuery.param(query, "Stamp");
    String opStamp = IclockQuery.param(query, "OpStamp");

    if (!service.persistenceEnabled()) {
      // NEVER acknowledge data we did not store. "OK" would let the terminal advance its cursor and
      // discard the batch — silent, permanent data loss if a device is ever pointed at an instance
      // where ingest is switched off. A non-OK reply makes it retain and retry instead.
      return unavailable();
    }
    if (table == null || !table.equalsIgnoreCase("ATTLOG")) {
      // OPERLOG / BIODATA / unknown: raw-logged only, but still record liveness and the cursor.
      service.touch(serial, false, null, stamp, opStamp);
      return text("OK");
    }
    if (serial != null && service.overPunchCap(serial)) {
      // An UNCLAIMED serial has banked its allowance. Answer OK and store nothing: a 503 here would
      // make a genuine terminal retry the same batch forever, and a JSON error would break it
      // outright. Claimed devices are never capped — see IclockService.overPunchCap.
      log.warn("iclock: SN={} is over the unclaimed-serial punch cap; accepting without storing", serial);
      return text(props.ackWithCount() ? "OK: " + IclockAttlog.parse(rawBody(request)).size() : "OK");
    }
    String logId = (String) request.getAttribute(IclockRequestLogFilter.ATTR_LOG_ID);
    IclockService.IngestResult result =
        service.ingestAttlog(serial, rawBody(request), logId, stamp);
    if (result.duplicates() > 0) {
      log.debug(
          "iclock: SN={} batch {} lines, {} new, {} duplicate",
          serial,
          result.lines(),
          result.inserted(),
          result.duplicates());
    }
    return text(props.ackWithCount() ? "OK: " + result.lines() : "OK");
  }

  /**
   * Catch-all for any other {@code /iclock} path or method — {@code devicecmd}, {@code rtdata},
   * {@code ping}, whatever this firmware probes, in either the bare or {@code .aspx} spelling.
   * MANDATORY, not defensive: without it those requests become a {@code NoHandlerFoundException} and
   * a JSON 404 the device would reject and retry forever. The capture filter has already recorded the
   * request verbatim, which is how the {@code .aspx} dialect was discovered in the first place.
   */
  @RequestMapping("/iclock/**")
  public ResponseEntity<byte[]> catchAll(HttpServletRequest request) {
    if (!service.persistenceEnabled()) {
      // A write method on an unknown path may well be carrying data. Same rule as above: do not
      // acknowledge what we did not store. Reads stay "OK" so a device is not error-looped.
      return isWrite(request) ? unavailable() : text("OK");
    }
    service.touch(IclockQuery.param(request.getQueryString(), "SN"), false, null, null, null);
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
   * <p>Note the trade-off: because every failure still returns 200, a completely broken ingest is
   * indistinguishable from a healthy one from outside. The signals to watch are this log line and the
   * row counts in {@code iclock_request_logs} / {@code iclock_raw_punches}.
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<byte[]> handleAnything(HttpServletRequest request, Exception e) {
    log.warn("iclock: handler error on {} {}", request.getMethod(), request.getRequestURI(), e);
    return text("OK");
  }

  /**
   * The body the capture filter already read. Falls back to reading the stream only if the filter did
   * not run, which should be impossible for a mapped {@code /iclock} path.
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
