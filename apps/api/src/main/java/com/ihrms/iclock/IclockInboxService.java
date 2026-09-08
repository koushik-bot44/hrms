package com.ihrms.iclock;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.iclock.dto.IclockAdminDtos.SweepResult;
import com.ihrms.iclock.dto.IclockAdminDtos.UnmappedInbox;
import com.ihrms.iclock.dto.IclockAdminDtos.UnmappedPinView;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's daily work surface: raw punches that never became effective punches, and the manual
 * promotion sweep.
 *
 * <p>Unresolvable punches are deliberately NOT dropped and NOT written to a rejects table — they stay
 * raw, and this service derives the inbox by asking which raw punches have no burst membership. That
 * keeps a single source of truth: fix the cause (claim the device, map the pin) and the same punches
 * simply promote on the next sweep, with no reject rows to clean up.
 */
@Service
public class IclockInboxService {

  private final IclockRawPunchRepository rawPunches;
  private final IclockDeviceRepository devices;
  private final IclockSiteRepository sites;
  private final IclockPersonRepository people;
  private final IclockPromotionService promotion;

  public IclockInboxService(
      IclockRawPunchRepository rawPunches,
      IclockDeviceRepository devices,
      IclockSiteRepository sites,
      IclockPersonRepository people,
      IclockPromotionService promotion) {
    this.rawPunches = rawPunches;
    this.devices = devices;
    this.sites = sites;
    this.people = people;
    this.promotion = promotion;
  }

  /**
   * The operator inbox, split by the live window.
   *
   * <p>The reason is computed here rather than in SQL so it can only ever be derived from the same
   * facts promotion uses — device claim state, then roster lookup.
   *
   * <p>{@code since} is the earliest device claim at the site: punches before it are archive (backfill
   * is off by policy), punches after it are attribution that is failing RIGHT NOW.
   */
  @Transactional(readOnly = true)
  public UnmappedInbox unmapped(String siteId, Instant since, int limit) {
    List<UnmappedPinView> live = new ArrayList<>();
    int archivePins = 0;
    long archivePunches = 0;

    for (Object[] row : rawPunches.findUnmappedPinSummarySince(siteId, since, limit)) {
      String devicePin = (String) row[0];
      String serial = (String) row[1];
      long count = ((Number) row[2]).longValue();
      Instant first = toInstant(row[3]);
      Instant last = toInstant(row[4]);
      long liveCount = ((Number) row[5]).longValue();

      if (liveCount == 0) {
        archivePins++;
        archivePunches += count;
        continue;
      }

      IclockDevice device = devices.findBySerialNumber(serial).orElse(null);
      String resolvedSite = device == null ? null : device.getSiteId();
      String siteName =
          resolvedSite == null ? null : sites.findById(resolvedSite).map(s -> s.getName()).orElse(null);
      String canonical = IclockPin.canonicalOrNull(devicePin);

      String reason;
      boolean inactive = false;
      String personId = null;
      if (device == null || !"CLAIMED".equals(device.getStatus())) {
        reason = "DEVICE_UNCLAIMED";
      } else if (canonical == null) {
        reason = "NO_PIN";
      } else {
        IclockPerson person =
            people.findBySiteIdAndPin(resolvedSite, canonical).orElse(null);
        if (person == null) {
          reason = "UNKNOWN_PIN";
        } else if (!person.isActive()) {
          // Not a missing identity — an existing one switched off. The remedy is to flip them
          // active, NOT to create a second person, so the console is told which case this is.
          reason = "INACTIVE_PERSON";
          inactive = true;
          personId = person.getId();
        } else {
          reason = "ANOMALY_OFFBOARDED";
          personId = person.getId();
        }
      }
      live.add(
          new UnmappedPinView(
              canonical == null ? devicePin : canonical, count, first, last,
              resolvedSite, siteName, reason, null, liveCount, inactive, personId));
    }
    return new UnmappedInbox(since, live, archivePins, archivePunches);
  }

  /**
   * Coerces whatever the driver hands back for a {@code timestamptz} into an {@link Instant}.
   *
   * <p>Deliberately type-tolerant. A native query returns driver types, not entity types, and pgjdbc
   * gives a {@code timestamptz} as an {@link OffsetDateTime} under JDBC 4.2 — not the
   * {@link java.sql.Timestamp} an older mapping would suggest. Casting to one concrete type threw
   * {@code ClassCastException} and surfaced as a 500 on the operator's main work surface, which is a
   * silly way to lose an endpoint. Accepting the family is both correct and future-proof.
   */
  private static Instant toInstant(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Instant i) {
      return i;
    }
    if (value instanceof OffsetDateTime odt) {
      return odt.toInstant();
    }
    if (value instanceof java.sql.Timestamp ts) {
      return ts.toInstant();
    }
    if (value instanceof java.util.Date d) {
      return d.toInstant();
    }
    throw new IllegalStateException(
        "Unexpected timestamp type from the driver: " + value.getClass().getName());
  }

  /**
   * Re-attempts promotion for EVERYTHING still unpromoted, back to the very first raw punch ever
   * captured. Idempotent.
   *
   * <p><b>This is the backfill primitive, and it is not safe to expose to a button.</b> It reaches the
   * entire archive and is ordered oldest-first, so on this deployment it would start by promoting the
   * oldest of ~33,000 punches captured before the terminals were ever adopted. Backfill is off by
   * policy; the only sanctioned caller is {@link IclockBackfillRunner}, which is gated behind the
   * one-shot {@code ihrms.iclock-backfill} flag. Anything operator-facing must call
   * {@link #sweepSinceClaim} instead.
   *
   * <p>Under P1a this was harmlessly inert — no roster existed, so every punch died at UNKNOWN_PIN
   * regardless of how far back the query reached. P1b is what makes it live.
   *
   * <p>Processed in {@code punchedAtRaw} order rather than arrival order, so a buffered flush promotes
   * the same way it would have live.
   */
  @Transactional
  public SweepResult sweep(int limit) {
    return promoteAll(rawPunches.findUnpromoted(limit));
  }

  /**
   * The operator's "I fixed the cause, try again" — bounded to punches that arrived at or after the
   * earliest device claim.
   *
   * <p>Same bound {@code reresolveSinceClaim} uses, for the same reason: punches after adoption failed
   * to attribute because something was misconfigured and is now fixed, whereas everything before it is
   * archive that backfill policy says to leave alone. With no claimed device there is no window at all,
   * so it does nothing rather than falling back to the whole archive.
   */
  /**
   * How far a punch may predate its own arrival and still count as live.
   *
   * <p>Covers a genuine buffered flush — this fleet once held ~10 minutes during a DNS outage — while
   * excluding a first-contact history dump, which arrives months late. Twelve hours is comfortably
   * more than any real buffer and far less than any archive.
   */
  static final int PRE_ADOPTION_GRACE_HOURS = 12;

  @Transactional
  public SweepResult sweepSinceClaim(int limit) {
    Instant earliestClaim =
        devices.findAll().stream()
            .filter(d -> "CLAIMED".equals(d.getStatus()))
            .map(IclockDevice::getClaimedAt)
            .filter(java.util.Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(null);
    if (earliestClaim == null) {
      return new SweepResult(0, 0, 0, List.of());
    }
    return promoteAll(
        rawPunches.findUnpromotedSince(earliestClaim, PRE_ADOPTION_GRACE_HOURS, limit));
  }

  private SweepResult promoteAll(List<IclockRawPunch> batch) {
    Map<IclockPromotionService.Outcome, Integer> tally =
        new EnumMap<>(IclockPromotionService.Outcome.class);
    int promoted = 0;
    for (IclockRawPunch raw : batch) {
      IclockPromotionService.Outcome outcome = promotion.promote(raw.getId());
      tally.merge(outcome, 1, Integer::sum);
      if (outcome.promoted()) {
        promoted++;
      }
    }
    List<String> byOutcome =
        tally.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).sorted().toList();
    return new SweepResult(batch.size(), promoted, batch.size() - promoted, byOutcome);
  }
}
