package com.ihrms.iclock;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * A deliberately generous per-IP limiter for {@code /iclock}, separate from the app-wide
 * {@link com.ihrms.security.RateLimitFilter}.
 *
 * <p>The global limiter must NOT be extended to cover {@code /iclock}: its 60-requests-per-minute
 * window would throttle a terminal that legitimately polls every few seconds, and its rejection body
 * is JSON, which the device would reject and retry forever. This one exists because {@code /iclock}
 * is the only unauthenticated write path in the app, and without any ceiling a drive-by request can
 * insert unbounded rows (CSRF is disabled app-wide and a {@code text/plain} POST is a CORS "simple
 * request", so no preflight stands in the way).
 *
 * <p>The default of 600/minute is roughly an order of magnitude above any real device's poll rate,
 * so a genuine terminal is never throttled. Over the limit the caller still receives {@code 200 OK}
 * in {@code text/plain} — never a JSON 429 — but nothing is persisted.
 *
 * <p>In-memory and per-instance, matching the existing limiter's single-instance assumption.
 */
@Component
public class IclockRateLimiter {

  private static final long WINDOW_MS = 60_000;
  private static final int MAX_TRACKED_IPS = 10_000;

  private record Counter(long windowStart, AtomicInteger hits) {}

  private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
  private final int limit;

  public IclockRateLimiter(IclockProperties props) {
    this.limit = props.rateLimitPerMinute();
  }

  /** @return true when this caller is within its window allowance. */
  public boolean allow(String clientIp) {
    if (counters.size() > MAX_TRACKED_IPS) {
      counters.clear(); // crude bound, matching RateLimitFilter's approach
    }
    long now = System.currentTimeMillis();
    Counter counter =
        counters.compute(
            clientIp == null ? "unknown" : clientIp,
            (ip, current) ->
                current == null || now - current.windowStart() >= WINDOW_MS
                    ? new Counter(now, new AtomicInteger(0))
                    : current);
    return counter.hits().incrementAndGet() <= limit;
  }
}
