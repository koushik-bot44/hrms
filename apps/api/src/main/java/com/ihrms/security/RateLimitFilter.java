package com.ihrms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.web.ApiErrors;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-IP rate limit on the unauthenticated public endpoints — the auth endpoints ({@code /auth/**},
 * including OTP request/verify) and the db-storage blob endpoint ({@code /storage/blobs/**}) — to
 * blunt brute-force / credential-stuffing and file-token abuse (§6 hardening). A fixed window of
 * {@value #LIMIT} requests per {@value #WINDOW_MS} ms per client IP; over the limit returns 429 with
 * the error envelope. In-memory + per-instance (sufficient for the single-instance v1 deployment).
 * Runs before the security chain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

  private static final int LIMIT = 60;
  private static final long WINDOW_MS = 60_000;
  private static final int MAX_TRACKED_IPS = 50_000;

  private record Counter(long windowStart, AtomicInteger hits) {}

  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final ObjectMapper mapper;

  public RateLimitFilter(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String uri = request.getRequestURI();
    return !(uri.startsWith("/auth/") || uri.startsWith("/storage/blobs/"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (counters.size() > MAX_TRACKED_IPS) {
      counters.clear(); // crude bound — fine for a single-instance, in-memory limiter
    }
    long now = System.currentTimeMillis();
    Counter counter =
        counters.compute(
            request.getRemoteAddr(),
            (ip, current) ->
                current == null || now - current.windowStart() >= WINDOW_MS
                    ? new Counter(now, new AtomicInteger(0))
                    : current);

    if (counter.hits().incrementAndGet() > LIMIT) {
      response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
      response.setContentType(MediaType.APPLICATION_JSON_VALUE);
      mapper.writeValue(
          response.getOutputStream(),
          ApiErrors.body(
              HttpStatus.TOO_MANY_REQUESTS.value(),
              "Too many requests — please slow down and try again shortly",
              request.getRequestURI()));
      return;
    }
    chain.doFilter(request, response);
  }
}
