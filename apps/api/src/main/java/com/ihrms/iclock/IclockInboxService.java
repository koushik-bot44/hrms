package com.ihrms.iclock;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockEmployeePinRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.dto.IclockAdminDtos.SweepResult;
import com.ihrms.iclock.dto.IclockAdminDtos.UnmappedPinView;
import java.sql.Timestamp;
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
  private final IclockEmployeePinRepository pins;
  private final IclockPromotionService promotion;

  public IclockInboxService(
      IclockRawPunchRepository rawPunches,
      IclockDeviceRepository devices,
      IclockSiteRepository sites,
      IclockEmployeePinRepository pins,
      IclockPromotionService promotion) {
    this.rawPunches = rawPunches;
    this.devices = devices;
    this.sites = sites;
    this.pins = pins;
    this.promotion = promotion;
  }

  /**
   * Pins that punched but resolve to nobody, newest-heaviest first.
   *
   * <p>The reason is computed here rather than in SQL so it can only ever be derived from the same
   * facts promotion uses — device claim state, then pin mapping.
   */
  @Transactional(readOnly = true)
  public List<UnmappedPinView> unmapped(int limit) {
    List<UnmappedPinView> out = new ArrayList<>();
    for (Object[] row : rawPunches.findUnmappedPinSummary(limit)) {
      String devicePin = (String) row[0];
      String serial = (String) row[1];
      long count = ((Number) row[2]).longValue();
      var first = row[3] == null ? null : ((Timestamp) row[3]).toInstant();
      var last = row[4] == null ? null : ((Timestamp) row[4]).toInstant();

      IclockDevice device = devices.findBySerialNumber(serial).orElse(null);
      String siteId = device == null ? null : device.getSiteId();
      String siteName = siteId == null ? null : sites.findById(siteId).map(s -> s.getName()).orElse(null);

      String reason;
      if (device == null || !"CLAIMED".equals(device.getStatus())) {
        reason = "DEVICE_UNCLAIMED";
      } else {
        String canonical = IclockPin.canonicalOrNull(devicePin);
        if (canonical == null) {
          reason = "NO_PIN";
        } else if (pins.findBySiteIdAndPin(siteId, canonical).isEmpty()) {
          reason = "UNKNOWN_PIN";
        } else {
          // The pin resolves, so the punch was declined for a per-punch reason — most often the
          // employee was already past their last working day (D4).
          reason = "ANOMALY_OFFBOARDED";
        }
      }
      out.add(new UnmappedPinView(
          IclockPin.canonical(devicePin), count, first, last, siteId, siteName, reason, null));
    }
    return out;
  }

  /**
   * Re-attempts promotion for everything still unpromoted. Idempotent, so it is safe to run twice, and
   * it is the "I fixed the cause, try again" button after a device is claimed or a pin is mapped.
   *
   * <p>Processed in {@code punchedAtRaw} order rather than arrival order, so a buffered flush promotes
   * the same way it would have live.
   */
  @Transactional
  public SweepResult sweep(int limit) {
    List<IclockRawPunch> batch = rawPunches.findUnpromoted(limit);
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
