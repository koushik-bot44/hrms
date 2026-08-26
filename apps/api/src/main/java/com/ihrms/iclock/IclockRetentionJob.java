package com.ihrms.iclock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prunes {@code iclock_request_logs}.
 *
 * <p><b>Punches are never touched by retention</b> — not raw ones, not effective ones. This job exists
 * only for the diagnostic capture table, which grows on every command poll (roughly one row per device
 * per {@code Delay} seconds, forever) and was always intended to be trimmed once the firmware dialect
 * was understood.
 *
 * <p>Single-instance only, following {@code BreakAlertScanner}: {@code @EnableScheduling} fires on
 * every instance, so a multi-instance deployment would run this concurrently. That is harmless here —
 * the deletes are idempotent and bounded — but it would double the work.
 *
 * <p><b>One caveat worth knowing.</b> The only thing preventing a re-uploaded ATTLOG line from being
 * stored twice is V42's {@code dedupeKey} unique index on {@code iclock_raw_punches} — NOT this table.
 * Pruning request logs therefore cannot cause duplicate punches. It does, however, destroy the replay
 * source: a body deleted here can no longer be re-parsed, so do not prune while any history is still
 * waiting to be promoted.
 */
@Component
public class IclockRetentionJob {

  private static final Logger log = LoggerFactory.getLogger(IclockRetentionJob.class);

  private final JdbcTemplate jdbc;
  private final IclockProperties props;

  public IclockRetentionJob(JdbcTemplate jdbc, IclockProperties props) {
    this.jdbc = jdbc;
    this.props = props;
  }

  /** Hourly. Cheap when there is nothing to do, and never bursty enough to matter. */
  @Scheduled(fixedDelayString = "${app.iclock.retention-scan-ms:3600000}", initialDelay = 120_000)
  public void prune() {
    if (!props.enabled()) {
      return;
    }
    try {
      int deleted =
          jdbc.update(
              "DELETE FROM \"iclock_request_logs\" WHERE \"receivedAt\" < now() - make_interval(days => ?)",
              props.logRetentionDays());
      int blanked = 0;
      if (props.nullBodiesAfterTwoDays()) {
        // A middle ground between keeping everything and deleting rows: the audit trail of WHICH
        // requests arrived survives, only the payloads go.
        blanked =
            jdbc.update(
                "UPDATE \"iclock_request_logs\" SET \"body\" = NULL"
                    + " WHERE \"receivedAt\" < now() - interval '2 days' AND \"body\" IS NOT NULL");
      }
      if (deleted > 0 || blanked > 0) {
        log.info(
            "iclock retention: deleted {} request log(s) older than {}d, blanked {} bod(ies)",
            deleted,
            props.logRetentionDays(),
            blanked);
      }
    } catch (Exception e) {
      // Retention must never take the app down or interrupt ingest.
      log.warn("iclock retention: prune failed; will retry on the next tick", e);
    }
  }
}
