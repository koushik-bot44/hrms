package com.ihrms.iclock;

import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockRequestLog;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates ADMS ingest on top of {@link IclockStore}. Intentionally carries NO
 * {@code @Transactional} of its own — each store call is its own transaction, and this class holds
 * the recovery logic that must sit outside them.
 *
 * <p>Everything except the raw capture is best-effort. Once the capture row is committed the device
 * is answered "OK" no matter what happens here, because that row is enough to replay the batch from
 * later; a non-OK answer would only make the terminal retry a batch we already hold.
 */
@Service
public class IclockService {

  private static final Logger log = LoggerFactory.getLogger(IclockService.class);

  /** Outcome of one ATTLOG batch. {@code lines} is what the device is acknowledged for. */
  public record IngestResult(int lines, int inserted, int duplicates) {}

  private final IclockStore store;
  private final IclockProperties props;

  public IclockService(IclockStore store, IclockProperties props) {
    this.store = store;
    this.props = props;
  }

  public boolean persistenceEnabled() {
    return props.enabled();
  }

  /**
   * Commits the raw capture row and returns its id. Deliberately NOT swallowed — the caller turns a
   * failure here into a non-OK response so the device retries rather than discarding punches we never
   * stored.
   */
  public String captureRequest(IclockRequestLog row) {
    return store.insertRequestLog(row);
  }

  /** Best-effort; a failure to stamp the response columns must never affect the device. */
  public void completeCapture(String id, Integer status, Integer durationMs) {
    if (id == null) {
      return;
    }
    try {
      store.completeRequestLog(id, status, durationMs);
    } catch (Exception e) {
      log.warn("iclock: could not complete capture row {}", id, e);
    }
  }

  /**
   * Upserts the device, tolerating the unique-index race on {@code serialNumber}. The re-read runs in
   * a fresh transaction because the failed insert has already marked its own transaction
   * rollback-only — re-reading inline would fail rather than return the winner's row.
   *
   * @return the device view, or null if it could not be resolved (punches are still stored)
   */
  public IclockStore.DeviceView resolveDevice(
      String serialNumber, boolean handshake, String firmwareInfo, String attlogStamp, String opStamp) {
    if (serialNumber == null || serialNumber.isBlank()) {
      return null;
    }
    try {
      return store.upsertDevice(serialNumber, handshake, firmwareInfo, attlogStamp, opStamp);
    } catch (DataIntegrityViolationException race) {
      // Another request created the same serial between our read and our insert.
      return store.findDeviceBySerial(serialNumber).orElse(null);
    } catch (Exception e) {
      log.warn("iclock: device upsert failed for SN={}", serialNumber, e);
      return null;
    }
  }

  /** Touches liveness for a non-ATTLOG request (handshake, command poll, OPERLOG/BIODATA push). */
  public IclockStore.DeviceView touch(
      String serialNumber, boolean handshake, String firmwareInfo, String attlogStamp, String opStamp) {
    return resolveDevice(serialNumber, handshake, firmwareInfo, attlogStamp, opStamp);
  }

  /**
   * Parses an ATTLOG body and stores one row per line, skipping any line already present.
   *
   * <p>{@link IngestResult#lines()} — not the inserted count — is what the device is acknowledged
   * for: the ack tells the terminal how much of its batch to retire, and the raw capture row already
   * holds every line, so acknowledging a line that deduped away loses nothing.
   */
  public IngestResult ingestAttlog(
      String serialNumber, String body, String requestLogId, String stamp) {
    List<IclockAttlog.Line> lines = IclockAttlog.parse(body);
    if (lines.isEmpty()) {
      return new IngestResult(0, 0, 0);
    }
    IclockStore.DeviceView device = resolveDevice(serialNumber, false, null, stamp, null);
    List<IclockRawPunch> rows =
        buildRows(serialNumber, device == null ? null : device.id(), lines, requestLogId, null);
    try {
      int inserted = store.insertPunches(rows);
      return new IngestResult(lines.size(), inserted, rows.size() - inserted);
    } catch (Exception e) {
      // The raw capture row is already committed, so the batch is recoverable from it.
      log.warn(
          "iclock: punch insert failed for SN={} ({} lines); raw capture {} retains the batch",
          serialNumber,
          rows.size(),
          requestLogId,
          e);
      return new IngestResult(lines.size(), 0, 0);
    }
  }

  /**
   * Builds punch rows from parsed lines. Shared by live ingest and the one-off replay so both compute
   * the dedupe key the same way — that shared key is the only thing preventing the replay from
   * double-counting lines that also arrived live.
   *
   * @param receivedAt the original request time for replay; null means "now" (live ingest)
   */
  List<IclockRawPunch> buildRows(
      String serialNumber,
      String deviceId,
      List<IclockAttlog.Line> lines,
      String requestLogId,
      Instant receivedAt) {
    List<IclockRawPunch> rows = new ArrayList<>(lines.size());
    for (IclockAttlog.Line line : lines) {
      // NOT NULL in V41 with no meaningful default — guard rather than trust the parser.
      String rawLine = line.rawLine() == null ? "" : line.rawLine();
      IclockRawPunch row = new IclockRawPunch();
      row.setDeviceId(deviceId);
      row.setSerialNumber(serialNumber);
      row.setDevicePin(line.pin());
      row.setPunchedAtRaw(line.punchedAtRaw());
      row.setStatusCode(line.statusCode());
      row.setVerifyMode(line.verifyMode());
      row.setWorkCode(line.workCode());
      row.setRawLine(rawLine);
      row.setLineNumber(line.lineNumber());
      row.setRequestLogId(requestLogId);
      row.setDedupeKey(IclockDedupe.key(serialNumber, rawLine));
      row.setReceivedAt(receivedAt);
      rows.add(row);
    }
    return rows;
  }

  /** Insert path for the replay, sharing the store's conflict-skipping behaviour. */
  int insertPunches(List<IclockRawPunch> rows) {
    return store.insertPunches(rows);
  }
}
