package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The punch feed: what the terminals actually sent, newest first.
 *
 * <p>Deliberately RAW rather than effective. The board answers "where is everyone", and to do that it
 * shows burst-collapsed punches attributed to people. This answers a different question — "what is
 * arriving at the server right now" — and the two disagree in exactly the cases worth watching: a pin
 * nobody can resolve, a terminal whose clock has drifted from the server's, a burst that swallowed four
 * reads. Making the operator infer any of that from the board is how a misconfigured reader goes
 * unnoticed for a week.
 *
 * <p>Read-only by construction, per standing rule #2 — this service has no write path at all.
 */
@Service
public class IclockFeedService {

  /** A ticker is worthless if it makes the operator wait, and useless if it is a wall of rows. */
  private static final int DEFAULT_PAGE = 100;
  private static final int MAX_PAGE = 500;

  private final IclockRawPunchRepository rawPunches;
  private final IclockSiteRepository sites;

  public IclockFeedService(IclockRawPunchRepository rawPunches, IclockSiteRepository sites) {
    this.rawPunches = rawPunches;
    this.sites = sites;
  }

  /** How a punch was attributed, as the feed reports it. */
  public record FeedRow(
      String rawPunchId,
      Instant receivedAt,
      /** The terminal's own clock, verbatim. Shown beside receivedAt because drift is the signal. */
      String deviceTime,
      String pin,
      String serialNumber,
      String deviceName,
      /** GATE or CAFETERIA; null when the terminal has never been claimed. */
      String area,
      /** IN, OUT or MIXED; null when unclaimed. */
      String direction,
      String siteId,
      String siteName,
      /** Null when the pin resolves to nobody — the prominent "Unknown pin" state. */
      String personId,
      String personName,
      String companyName,
      /**
       * WHICH CREDENTIAL GOT THEM THROUGH. Face on 98.5% of this fleet's punches, finger on 1.1%.
       * Carried on every row because "they badged" and "their face was recognised" are different
       * facts, and only one of them is replaceable by a template this system can push.
       */
      String verifyMode,
      String verifyLabel,
      /**
       * True when this raw row was absorbed into a burst whose KEPT punch is a different row. It is
       * real data that the effective view deliberately hides, so the feed says so rather than
       * pretending the punch never happened.
       */
      boolean collapsedAway) {}

  public record Feed(
      List<FeedRow> rows,
      long total,
      int page,
      int size,
      /** The window actually applied, so the console can label what it is showing. */
      Instant from,
      Instant to) {}

  /** All / Unknown only / Rostered only — the attribution filter the console offers. */
  public enum Attribution {
    ALL,
    UNKNOWN,
    ROSTERED
  }

  /**
   * One page of the feed.
   *
   * <p>{@code siteId} null means every building, which is what a fleet-wide view has to mean; a
   * non-null value is a filter on the same query rather than a separate path. {@code shiftDate} null
   * defaults to the CURRENT shift day, so opening the tab shows tonight rather than all history — the
   * archive is 34,000 rows and growing, and a ticker that opens on 2026-06 is not a ticker.
   */
  @Transactional(readOnly = true)
  public Feed feed(
      String siteId,
      String deviceId,
      LocalDate shiftDate,
      Attribution attribution,
      int page,
      int size) {
    if (siteId != null && sites.findById(siteId).isEmpty()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "Building not found");
    }

    LocalDate day = shiftDate != null ? shiftDate : ShiftConfig.shiftDateOf(Instant.now());
    // The shift-day window, using the SAME cut the pipeline uses — so "today" on this screen means
    // exactly what "today" means everywhere else, including across the 04:00 shift end.
    Instant from = day.atTime(ShiftConfig.DAY_CUT).atZone(ShiftConfig.ZONE).toInstant();
    Instant to = day.plusDays(1).atTime(ShiftConfig.DAY_CUT).atZone(ShiftConfig.ZONE).toInstant();

    int safeSize = Math.min(Math.max(size <= 0 ? DEFAULT_PAGE : size, 1), MAX_PAGE);
    int safePage = Math.max(page, 0);
    boolean unknownOnly = attribution == Attribution.UNKNOWN;
    boolean rosteredOnly = attribution == Attribution.ROSTERED;

    List<FeedRow> rows = new ArrayList<>();
    for (Object[] r :
        rawPunches.findFeed(
            siteId, deviceId, from, to, unknownOnly, rosteredOnly, safeSize, safePage * safeSize)) {
      rows.add(
          new FeedRow(
              (String) r[0],
              toInstant(r[1]),
              (String) r[2],
              IclockPin.canonicalOrNull((String) r[3]) == null
                  ? (String) r[3]
                  : IclockPin.canonicalOrNull((String) r[3]),
              (String) r[4],
              (String) r[5],
              (String) r[6],
              (String) r[7],
              (String) r[8],
              (String) r[9],
              (String) r[10],
              (String) r[11],
              (String) r[12],
              IclockVerifyMode.of((String) r[13]).name(),
              IclockVerifyMode.of((String) r[13]).label(),
              Boolean.TRUE.equals(r[14])));
    }

    long total = rawPunches.countFeed(siteId, deviceId, from, to, unknownOnly, rosteredOnly);
    return new Feed(rows, total, safePage, safeSize, from, to);
  }

  /**
   * Coerces a driver timestamp into an {@link Instant}.
   *
   * <p>Type-tolerant on purpose: a native query returns DRIVER types, and pgjdbc hands back a
   * {@code timestamptz} as an {@link OffsetDateTime} under JDBC 4.2. Casting to one concrete type is
   * what took the operator inbox down with a 500 once already.
   */
  private static Instant toInstant(Object value) {
    if (value == null) return null;
    if (value instanceof Instant i) return i;
    if (value instanceof OffsetDateTime odt) return odt.toInstant();
    if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
    if (value instanceof java.util.Date d) return d.toInstant();
    throw new IllegalStateException("Unexpected timestamp type from the driver: " + value.getClass());
  }
}
