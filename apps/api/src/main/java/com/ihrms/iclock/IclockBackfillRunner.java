package com.ihrms.iclock;

import com.ihrms.iclock.dto.IclockAdminDtos.SweepResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * One-off backfill: derives effective punches from ALL existing raw history.
 *
 * <p>Enabled for a SINGLE boot via {@code ihrms.iclock-backfill=true}, mirroring the existing
 * {@code FieldKeyReencryptor} and {@code IclockReplayRunner} convention, then unset.
 *
 * <p><b>Order of operations matters.</b> Claim the devices and load the pin mappings FIRST. Run this
 * against an unclaimed fleet and every punch is simply declined as {@code DEVICE_UNCLAIMED} — harmless
 * and re-runnable, but pointless. There is no partial-credit state to clean up either way, which is
 * the advantage of leaving unresolvable punches raw rather than writing reject rows.
 *
 * <p><b>Idempotent by construction.</b> It tracks nothing: {@code iclock_punch_members.rawPunchId} is
 * uniquely indexed, so an already-absorbed punch is skipped. Re-running is a provable no-op.
 */
@Component
public class IclockBackfillRunner implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(IclockBackfillRunner.class);
  /** Bounded per pass so a huge history cannot hold one transaction open indefinitely. */
  private static final int BATCH = 2_000;

  private final IclockInboxService inbox;
  private final boolean enabled;

  public IclockBackfillRunner(
      IclockInboxService inbox, @Value("${ihrms.iclock-backfill:false}") boolean enabled) {
    this.inbox = inbox;
    this.enabled = enabled;
  }

  @Override
  public void run(String... args) {
    if (!enabled) {
      return;
    }
    log.info("iclock backfill: starting — deriving effective punches from raw history");
    long scanned = 0, promoted = 0;
    java.util.Map<String, Integer> outcomes = new java.util.TreeMap<>();

    while (true) {
      SweepResult pass = inbox.sweep(BATCH);
      if (pass.scanned() == 0) {
        break;
      }
      scanned += pass.scanned();
      promoted += pass.promoted();
      for (String entry : pass.byOutcome()) {
        String[] kv = entry.split("=", 2);
        outcomes.merge(kv[0], Integer.parseInt(kv[1]), Integer::sum);
      }
      // A pass that promotes nothing means everything left is genuinely unresolvable (unclaimed
      // device, unmapped pin, post-offboarding). Stop rather than spinning over the same rows.
      if (pass.promoted() == 0) {
        break;
      }
    }
    log.info(
        "iclock backfill: COMPLETE — {} raw scanned, {} promoted, {} left unresolved. Outcomes: {}",
        scanned,
        promoted,
        scanned - promoted,
        outcomes);
  }
}
