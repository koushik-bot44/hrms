package com.ihrms.iclock;

import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockRequestLog;
import com.ihrms.domain.repository.IclockRequestLogRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * One-off backfill: re-parses ATTLOG bodies already captured in {@code iclock_request_logs} into
 * {@code iclock_raw_punches}.
 *
 * <p>Exists because P0 only mapped the bare {@code /iclock/cdata} path while the deployed firmware
 * calls {@code /iclock/cdata.aspx}, so real pushes landed on the catch-all: captured verbatim, but
 * never parsed. That was the design working as intended — the raw row was always meant to be the
 * replay source — and this is the replay.
 *
 * <p><b>Idempotent by construction, not by bookkeeping.</b> It does not track which rows it has
 * processed. Every punch carries {@code dedupeKey = md5(serial + "\n" + rawLine)} under a unique
 * index (V42), and the insert is {@code ON CONFLICT DO NOTHING}, so re-running is a no-op and racing
 * a live push is safe. Live ingest computes the identical key through the identical code path
 * ({@link IclockService#buildRows}), which is what makes double-counting impossible rather than
 * merely unlikely.
 *
 * <p>Enabled for a SINGLE run via {@code ihrms.iclock-replay=true}, mirroring the existing
 * {@code FieldKeyReencryptor} convention, then unset. Leaving it on is harmless but pointless.
 */
@Component
public class IclockReplayRunner implements CommandLineRunner {

  private static final Logger log = LoggerFactory.getLogger(IclockReplayRunner.class);
  private static final int PAGE_SIZE = 200;

  private final IclockRequestLogRepository logs;
  private final IclockService service;
  private final boolean enabled;

  public IclockReplayRunner(
      IclockRequestLogRepository logs,
      IclockService service,
      @Value("${ihrms.iclock-replay:false}") boolean enabled) {
    this.logs = logs;
    this.service = service;
    this.enabled = enabled;
  }

  @Override
  public void run(String... args) {
    if (!enabled) {
      return;
    }
    log.info("iclock replay: starting — re-parsing captured ATTLOG bodies into iclock_raw_punches");
    long bodies = 0;
    long linesIn = 0;
    long rowsOut = 0;
    long dupes = 0;
    Map<String, String> deviceIdBySerial = new HashMap<>();

    Pageable page = PageRequest.of(0, PAGE_SIZE);
    while (true) {
      Page<IclockRequestLog> batch =
          logs.findByTableNameIgnoreCaseOrderByReceivedAtAsc("ATTLOG", page);
      if (batch.isEmpty()) {
        break;
      }
      for (IclockRequestLog row : batch) {
        String serial = row.getSerialNumber();
        if (row.getBody() == null || row.getBody().isBlank() || serial == null || serial.isBlank()) {
          // A lean-mode or truncated row has nothing to replay; skip rather than invent lines.
          continue;
        }
        List<IclockAttlog.Line> lines = IclockAttlog.parse(row.getBody());
        if (lines.isEmpty()) {
          continue;
        }
        bodies++;
        linesIn += lines.size();

        String deviceId =
            deviceIdBySerial.computeIfAbsent(
                serial,
                sn -> {
                  IclockStore.DeviceView d = service.resolveDevice(sn, false, null, null, null);
                  return d == null ? null : d.id();
                });

        List<IclockRawPunch> punches =
            service.buildRows(serial, deviceId, lines, row.getId(), row.getReceivedAt());
        try {
          // Same write path as live ingest: persist, then promote. Sharing it is what keeps the two
          // from diverging the way P0 and P0.1 did.
          int inserted = service.persistAndPromote(punches);
          rowsOut += inserted;
          dupes += (punches.size() - inserted);
        } catch (Exception e) {
          // One bad body must not abort the whole backfill; the raw row stays and can be retried.
          log.warn("iclock replay: batch from capture {} failed", row.getId(), e);
        }
      }
      if (!batch.hasNext()) {
        break;
      }
      page = batch.nextPageable();
    }

    log.info(
        "iclock replay: COMPLETE — {} ATTLOG bodies, {} lines in, {} rows inserted, {} duplicates skipped",
        bodies,
        linesIn,
        rowsOut,
        dupes);
  }
}
