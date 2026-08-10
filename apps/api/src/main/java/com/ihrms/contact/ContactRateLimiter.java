package com.ihrms.contact;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Abuse control for the PUBLIC contact endpoint (§8d): a small in-memory, fixed-window limiter — at most
 * {@value #PER_IP_LIMIT} enquiries per client IP per hour AND {@value #GLOBAL_LIMIT} across all IPs per hour;
 * over either limit → 429 with a friendly message. Per-INSTANCE (single-instance v1); a shared store (Redis)
 * would be the future hardening for multi-instance. No PII is retained — only IP → (window start, count).
 */
@Component
public class ContactRateLimiter {

  static final int PER_IP_LIMIT = 5;
  static final int GLOBAL_LIMIT = 20;
  private static final long WINDOW_MS = 3_600_000; // 1 hour
  private static final int MAX_TRACKED_IPS = 50_000;

  private record Window(long start, int count) {}

  private final ConcurrentHashMap<String, Window> perIp = new ConcurrentHashMap<>();
  private volatile Window global = new Window(0, 0);

  /** Record one enquiry from {@code ip}; throws 429 if the per-IP or global hourly cap is already reached. */
  public synchronized void check(String ip) {
    long now = System.currentTimeMillis();

    // Global window.
    if (now - global.start() >= WINDOW_MS) {
      global = new Window(now, 0);
    }
    if (global.count() >= GLOBAL_LIMIT) {
      throw tooMany();
    }

    // Per-IP window.
    if (perIp.size() > MAX_TRACKED_IPS) {
      perIp.clear(); // crude bound — fine for an in-memory, single-instance limiter
    }
    Window ipWindow = perIp.get(ip);
    if (ipWindow == null || now - ipWindow.start() >= WINDOW_MS) {
      ipWindow = new Window(now, 0);
    }
    if (ipWindow.count() >= PER_IP_LIMIT) {
      throw tooMany();
    }

    // Record the hit under both windows.
    perIp.put(ip, new Window(ipWindow.start(), ipWindow.count() + 1));
    global = new Window(global.start(), global.count() + 1);
  }

  /** Test hook — clears all windows so cases don't leak counts into each other. */
  public synchronized void reset() {
    perIp.clear();
    global = new Window(0, 0);
  }

  private static ResponseStatusException tooMany() {
    return new ResponseStatusException(
        HttpStatus.TOO_MANY_REQUESTS,
        "Too many requests right now — please try again later, or email info@hrorg.in");
  }
}
