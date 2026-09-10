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
  /** The command queue. Serves at most one line per poll, and only when the kill switch is on. */
  private final IclockCommandService commands;
  private final IclockBiometricService biometrics;
  /** Seeds roster rows from a device's own user table, when one ever arrives. */
  private final IclockEnrolmentService enrolments;

  public IclockController(
      IclockService service,
      IclockProperties props,
      IclockCommandService commands,
      IclockBiometricService biometrics,
      IclockEnrolmentService enrolments) {
    this.service = service;
    this.props = props;
    this.commands = commands;
    this.biometrics = biometrics;
    this.enrolments = enrolments;
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
   * Command poll, every {@code Delay} seconds — and since P3, the one place the server can tell a
   * terminal to do something.
   *
   * <p>The reply is either a bare {@code OK} (nothing to say, which is the overwhelming majority of
   * ~119,000 polls so far) or a single command line. One at a time: this firmware acknowledges by
   * id, so serving a batch would leave us unable to tell which of them landed.
   *
   * <p><b>Failing to serve must never fail the poll.</b> A terminal that receives an error instead
   * of a reply retries in a tight loop, so the queue lookup is wrapped: if anything goes wrong the
   * device still gets its {@code OK} and the command stays queued for the next poll.
   */
  @GetMapping({"/iclock/getrequest", "/iclock/getrequest.aspx"})
  public ResponseEntity<byte[]> getrequest(HttpServletRequest request) {
    String query = request.getQueryString();
    String serial = IclockQuery.param(query, "SN");
    if (service.persistenceEnabled()) {
      // Some polls carry &INFO=<firmware,counters,...>; keep it, it is free dialect evidence.
      String info = IclockQuery.param(query, "INFO");
      service.touch(serial, false, info == null ? null : query, null, null);
    }
    if (service.persistenceEnabled()) {
      try {
        var next = commands.nextFor(serial);
        if (next.isPresent()) {
          return text(next.get());
        }
      } catch (RuntimeException e) {
        log.warn("iclock: command lookup failed for SN={}; answering OK", serial, e);
      }
    }
    return text("OK");
  }

  /**
   * A device reporting what it did with a command.
   *
   * <p><b>The shape here is documented, not observed.</b> No terminal on this fleet has ever called
   * this path — the request log holds zero rows for it — because none has ever been sent a command.
   * The classic form is {@code ID=<id>&Return=<code>&CMD=<verb>}, carried as a POST body or as query
   * parameters depending on firmware, so both are read.
   *
   * <p>Always answers OK. The capture filter has already stored the request verbatim, so an ack in a
   * shape nobody predicted is preserved for reading rather than rejected — which is exactly how the
   * {@code .aspx} dialect was found in the first place.
   */
  @RequestMapping({"/iclock/devicecmd", "/iclock/devicecmd.aspx"})
  public ResponseEntity<byte[]> devicecmd(HttpServletRequest request) {
    if (!service.persistenceEnabled()) {
      return text("OK");
    }
    String query = request.getQueryString();
    String body = rawBody(request);
    service.touch(IclockQuery.param(query, "SN"), false, null, null, null);

    // Either carrier. A firmware that puts these in the query string is as plausible as one that
    // posts them, and guessing wrong would silently drop every acknowledgement.
    String id = firstNonBlank(IclockQuery.param(query, "ID"), formValue(body, "ID"));
    String ret = firstNonBlank(IclockQuery.param(query, "Return"), formValue(body, "Return"));
    try {
      // A template ack is routed onward: it says a terminal has taken a fingerprint into its own
      // store, which is what "enrolled on 4 of 4" is counting. Routed HERE rather than inside the
      // command service, because propagation queues through that service and the dependency would
      // otherwise point both ways.
      commands.recordAck(id, ret, body)
          .filter(a -> "UPDATE_FINGERTMP".equals(a.kind()))
          .ifPresent(a -> biometrics.recordTemplateAck(a.commandId(), a.returnValue()));
    } catch (RuntimeException e) {
      log.warn("iclock: failed to record devicecmd ack; body captured raw: {}", body, e);
    }
    return text("OK");
  }

  /** Reads {@code key=value} out of an {@code &}-joined or newline-joined ack body. */
  static String formValue(String body, String key) {
    if (body == null || body.isBlank()) {
      return null;
    }
    for (String part : body.split("[&\r\n]")) {
      int eq = part.indexOf('=');
      if (eq > 0 && part.substring(0, eq).trim().equalsIgnoreCase(key)) {
        return part.substring(eq + 1).trim();
      }
    }
    return null;
  }

  private static String firstNonBlank(String a, String b) {
    return (a != null && !a.isBlank()) ? a : b;
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
    if (table != null && table.equalsIgnoreCase("USERINFO")) {
      // A device telling us who it thinks its users are. This fleet has never sent one — 129,747
      // logged requests, zero bodies containing Name= — but this is the only channel by which a
      // terminal's own name for somebody could reach us, and it costs nothing to be ready. Seeds
      // roster rows for pins nobody knows yet; NEVER overwrites a name a human entered.
      service.touch(serial, false, null, stamp, opStamp);
      try {
        var seeded = enrolments.apply(serial, rawBody(request));
        if (seeded.created() > 0 || seeded.suggestions() > 0) {
          log.info("iclock: SN={} USERINFO {} record(s): {} seeded, {} differ from the roster",
              serial, seeded.records(), seeded.created(), seeded.suggestions());
        }
      } catch (RuntimeException e) {
        // Never cost the push. The body is captured raw regardless, so a parse failure loses
        // nothing except the convenience of having read it.
        log.warn("iclock: SN={} USERINFO parse failed; body captured raw", serial, e);
      }
      return text("OK");
    }
    if (table == null || !table.equalsIgnoreCase("ATTLOG")) {
      // OPERLOG / unknown: raw-logged, liveness and cursor recorded — and, for OPERLOG, mined for
      // fingerprints.
      //
      // THE TEMPLATES RIDE IN HERE. The OPLOG lines are operation records that say something
      // happened to a pin and never what that person is called, which is why this branch used to do
      // nothing. But the same body carries FP records when somebody enrols at the terminal, and
      // those are complete fingerprint templates arriving unasked. That is the capture half of
      // building-wide propagation, free.
      service.touch(serial, false, null, stamp, opStamp);
      if (table != null && table.equalsIgnoreCase("OPERLOG")) {
        try {
          biometrics.capture(serial, rawBody(request));
        } catch (RuntimeException e) {
          // Never cost the push. A terminal that gets an error retries the batch forever, and the
          // body is captured raw regardless, so a failure here loses a propagation and not a
          // fingerprint — and there is a re-sync action for exactly that.
          log.warn("iclock: SN={} OPERLOG template capture failed; body captured raw", serial, e);
        }
      }
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
