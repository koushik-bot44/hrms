package com.ihrms.iclock;

import com.ihrms.domain.model.IclockRequestLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Captures every {@code /iclock} request verbatim, and is the ingest commit point.
 *
 * <p><b>Ordering.</b> Registered ahead of the security chain so a request is recorded even if
 * something downstream would reject it — the dialect of this firmware is unknown, and a request we
 * refuse is exactly the one we most need to see. The consequence is that this filter also sees
 * credentials that were never meant for it, which is why sensitive headers are redacted below.
 *
 * <p><b>Commit-point rule.</b> The capture row is committed BEFORE the handler runs. After that
 * point every downstream failure is swallowed and the device is told "OK", because the raw row is a
 * sufficient replay source and a non-OK answer would only trigger a redundant re-push. If the
 * capture itself fails, the request deliberately fails too: the terminal keeps the punches in its
 * own memory and retries, which is far better than acknowledging data we never stored.
 *
 * <p><b>Never {@code sendError}.</b> Any call to it would hand the request to the container's ERROR
 * dispatch, which lands on {@code /error} — not permitted for this device — and returns JSON. Every
 * response this filter writes goes out through {@link #writeText} instead.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1000)
public class IclockRequestLogFilter extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(IclockRequestLogFilter.class);

  /** Request attributes the controller reads, so it never re-reads the stream itself. */
  static final String ATTR_LOG_ID = "ihrms.iclock.requestLogId";

  static final String ATTR_BODY = "ihrms.iclock.rawBody";

  /**
   * Headers never written to the database. This filter runs before authentication, so a stray probe,
   * a misconfigured client or a scanner can arrive carrying a live bearer token — and
   * {@code iclock_request_logs} is unencrypted, unretained, and readable by anything with DB access.
   * The refresh cookie is path-scoped to {@code /auth} so a browser will not send it here, but
   * {@code Authorization} is not scoped at all.
   */
  private static final Set<String> REDACTED =
      Set.of(
          "authorization",
          "proxy-authorization",
          "cookie",
          "set-cookie",
          "x-api-key",
          "x-auth-token");

  private final IclockService service;
  private final IclockRateLimiter limiter;
  private final IclockProperties props;

  public IclockRequestLogFilter(
      IclockService service, IclockRateLimiter limiter, IclockProperties props) {
    this.service = service;
    this.limiter = limiter;
    this.props = props;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    // The bare "/iclock" (no trailing slash) is matched by both the security matcher and the MVC
    // pattern "/iclock/**", so a startsWith("/iclock/") test alone would let a handled, 200-returning
    // request go unlogged — the exact blind spot this filter exists to close.
    return !("/iclock".equals(uri) || uri.startsWith("/iclock/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    long startNs = System.nanoTime();
    Body body = readBounded(request, props.maxBodyBytes());
    HttpServletRequest wrapped = new IclockCachedBodyRequest(request, body.bytes());
    wrapped.setAttribute(ATTR_BODY, body.text());

    // Over the ceiling: answer in text/plain so a real device is never broken by it, but persist
    // nothing. A genuine terminal polls far below this rate and will never see it.
    if (!limiter.allow(request.getRemoteAddr())) {
      writeText(response, HttpServletResponse.SC_OK, "OK");
      return;
    }

    String logId = null;
    if (service.persistenceEnabled()) {
      try {
        logId = service.captureRequest(buildRow(request, body));
        wrapped.setAttribute(ATTR_LOG_ID, logId);
      } catch (Exception e) {
        // The commit point failed (DB down, schema drift). Do NOT answer OK for data we did not
        // store — a non-OK reply makes the terminal retain and re-push the batch.
        log.error("iclock: raw capture failed; refusing the request so the device retries", e);
        writeText(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "");
        return;
      }
    }

    try {
      chain.doFilter(wrapped, response);
    } catch (Exception e) {
      // Raw capture already committed, so the batch is recoverable — keep the device moving.
      // Catches Exception, never Throwable: an OutOfMemoryError must propagate rather than be
      // masked behind an "OK" that keeps a dying process being pushed to.
      log.warn("iclock: handler failed after capture {}; answering OK", logId, e);
      if (!response.isCommitted()) {
        writeText(response, HttpServletResponse.SC_OK, "OK");
      }
    } finally {
      service.completeCapture(
          logId, response.getStatus(), (int) ((System.nanoTime() - startNs) / 1_000_000L));
    }
  }

  private IclockRequestLog buildRow(HttpServletRequest request, Body body) {
    String query = request.getQueryString();
    IclockRequestLog row = new IclockRequestLog();
    row.setSerialNumber(IclockQuery.param(query, "SN"));
    row.setMethod(request.getMethod());
    row.setPath(request.getRequestURI());
    row.setQueryString(query);
    row.setHeaders(headerDump(request));
    row.setBody(body.text());
    row.setBodyBytes(body.bytes().length);
    row.setBodyTruncated(body.truncated());
    row.setSanitized(body.sanitized());
    row.setRemoteAddr(request.getRemoteAddr());
    row.setTableName(IclockQuery.param(query, "table"));
    return row;
  }

  /** All headers, one per line, with sensitive values replaced rather than dropped. */
  private String headerDump(HttpServletRequest request) {
    StringBuilder sb = new StringBuilder(256);
    Enumeration<String> names = request.getHeaderNames();
    while (names != null && names.hasMoreElements()) {
      String name = names.nextElement();
      String value =
          REDACTED.contains(name.toLowerCase(Locale.ROOT))
              ? "[REDACTED]"
              : String.join(",", java.util.Collections.list(request.getHeaders(name)));
      sb.append(name).append(": ").append(value).append('\n');
    }
    return sb.toString();
  }

  /**
   * Reads at most {@code cap} bytes. There is NO server-level request-size limit in this application
   * (the "10 MiB cap" elsewhere is a check local to the storage controller), and this is the only
   * unauthenticated write endpoint, so an unbounded read here would be a trivial heap-exhaustion
   * DoS. Anything beyond the cap is left unread and the row is flagged truncated.
   */
  private Body readBounded(HttpServletRequest request, int cap) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(cap, 8192));
    boolean truncated = false;
    try (InputStream in = request.getInputStream()) {
      byte[] chunk = new byte[8192];
      int total = 0;
      int read;
      while ((read = in.read(chunk)) != -1) {
        if (total + read > cap) {
          buffer.write(chunk, 0, Math.max(0, cap - total));
          truncated = true;
          break;
        }
        buffer.write(chunk, 0, read);
        total += read;
      }
    } catch (IOException e) {
      log.warn("iclock: could not read request body", e);
    }
    byte[] bytes = buffer.toByteArray();
    // ISO-8859-1 is a lossless byte-to-char mapping, so an unexpected binary table (FDATA templates,
    // photos) survives as text rather than being mangled by a strict UTF-8 decode.
    String text = new String(bytes, StandardCharsets.ISO_8859_1);
    boolean sanitized = text.indexOf('\0') >= 0;
    if (sanitized) {
      // Postgres TEXT cannot hold a NUL byte; without stripping, the INSERT fails and we would lose
      // the capture for precisely the payload we least understand.
      text = text.replace("\0", "");
    }
    return new Body(bytes, text, truncated, sanitized);
  }

  private record Body(byte[] bytes, String text, boolean truncated, boolean sanitized) {}

  /**
   * Writes a bare {@code text/plain} response. Uses the output stream rather than {@code getWriter()}
   * so no {@code charset=} parameter is appended to the Content-Type — some ADMS firmware is fussy
   * about the exact header, and this keeps it deterministic.
   */
  private static void writeText(HttpServletResponse response, int status, String payload)
      throws IOException {
    response.setStatus(status);
    response.setContentType("text/plain");
    byte[] bytes = payload.getBytes(StandardCharsets.ISO_8859_1);
    response.setContentLength(bytes.length);
    response.getOutputStream().write(bytes);
    response.flushBuffer();
  }
}
