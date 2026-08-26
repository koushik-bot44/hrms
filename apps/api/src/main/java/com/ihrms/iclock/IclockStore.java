package com.ihrms.iclock;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockRequestLog;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockRequestLogRepository;
import com.ihrms.domain.support.Cuids;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional primitives for ADMS ingest. Deliberately a separate bean from
 * {@link IclockService}: every method here runs in its OWN transaction ({@code REQUIRES_NEW}), and
 * the retry/fallback logic that calls them must therefore live in a different bean — a
 * self-invocation would bypass the proxy and silently run in the caller's transaction.
 *
 * <p>Independent transactions are what make the "raw capture is the commit point" rule work: the
 * request-log row is committed before the handler runs, so a later failure to parse or to insert
 * punches cannot roll it back.
 */
@Service
public class IclockStore {

  /** Just enough of a device for the handshake to answer with, without leaking a JPA entity around. */
  public record DeviceView(String id, String attlogStamp, String opStamp, boolean claimed) {}

  private static final String INSERT_PUNCH =
      """
      INSERT INTO "iclock_raw_punches"
        ("id","deviceId","serialNumber","devicePin","punchedAtRaw","statusCode","verifyMode",
         "workCode","rawLine","lineNumber","requestLogId","dedupeKey","receivedAt")
      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT ("dedupeKey") DO NOTHING
      """;

  private final IclockDeviceRepository devices;
  private final IclockRequestLogRepository logs;
  private final JdbcTemplate jdbc;

  public IclockStore(
      IclockDeviceRepository devices, IclockRequestLogRepository logs, JdbcTemplate jdbc) {
    this.devices = devices;
    this.logs = logs;
    this.jdbc = jdbc;
  }

  /**
   * Commits the raw capture row. This is the ingest commit point — if it throws, the caller must let
   * the request fail so the device retries and keeps the punches in its own memory.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String insertRequestLog(IclockRequestLog row) {
    return logs.saveAndFlush(row).getId();
  }

  /** Best-effort completion of an already-committed capture row. Failures here are not fatal. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completeRequestLog(String id, Integer status, Integer durationMs) {
    logs.completeRequest(id, status, durationMs);
  }

  /**
   * Creates the device on first contact, or touches its liveness columns and registry stamps. Throws
   * {@code DataIntegrityViolationException} when it loses the unique-index race on
   * {@code serialNumber} — the caller recovers by re-reading in a fresh transaction, which is only
   * possible because this transaction is its own.
   *
   * @param attlogStamp persisted only when non-null (i.e. the device actually reported one)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public DeviceView upsertDevice(
      String serialNumber,
      boolean handshake,
      String firmwareInfo,
      String attlogStamp,
      String opStamp) {
    Instant now = Instant.now();
    IclockDevice device =
        devices
            .findBySerialNumber(serialNumber)
            .orElseGet(
                () -> {
                  IclockDevice fresh = new IclockDevice();
                  fresh.setSerialNumber(serialNumber);
                  return fresh;
                });
    device.setLastSeenAt(now);
    if (handshake) {
      device.setLastHandshakeAt(now);
      if (firmwareInfo != null && !firmwareInfo.isBlank()) {
        device.setFirmwareInfo(firmwareInfo);
      }
    }
    if (attlogStamp != null && !attlogStamp.isBlank()) {
      device.setAttlogStamp(attlogStamp);
    }
    if (opStamp != null && !opStamp.isBlank()) {
      device.setOpStamp(opStamp);
    }
    IclockDevice saved = devices.saveAndFlush(device);
    return new DeviceView(
        saved.getId(), saved.getAttlogStamp(), saved.getOpStamp(), "CLAIMED".equals(saved.getStatus()));
  }

  /** How many devices have never been adopted — the self-announce cap's input. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public long countUnclaimedDevices() {
    return devices.countByStatus("UNCLAIMED");
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<DeviceView> findDeviceBySerial(String serialNumber) {
    return devices
        .findBySerialNumber(serialNumber)
        .map(d -> new DeviceView(
            d.getId(), d.getAttlogStamp(), d.getOpStamp(), "CLAIMED".equals(d.getStatus())));
  }

  /**
   * Inserts a batch of punches, skipping any line already stored.
   *
   * <p>Uses {@code ON CONFLICT ("dedupeKey") DO NOTHING} rather than a read-then-write check so the
   * decision is made atomically by the database. That matters because the one-off replay and a live
   * push can run concurrently over the same lines — an application-side "does it exist?" check would
   * race between them and double-insert.
   *
   * @return how many rows were actually inserted; {@code rows.size()} minus this is the dupes skipped
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int insertPunches(List<IclockRawPunch> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    int[] results =
        jdbc.batchUpdate(
            INSERT_PUNCH,
            new BatchPreparedStatementSetter() {
              @Override
              public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                IclockRawPunch r = rows.get(i);
                ps.setString(1, Cuids.newId());
                setNullable(ps, 2, r.getDeviceId());
                ps.setString(3, r.getSerialNumber());
                setNullable(ps, 4, r.getDevicePin());
                setNullable(ps, 5, r.getPunchedAtRaw());
                setNullable(ps, 6, r.getStatusCode());
                setNullable(ps, 7, r.getVerifyMode());
                setNullable(ps, 8, r.getWorkCode());
                ps.setString(9, r.getRawLine());
                ps.setInt(10, r.getLineNumber());
                setNullable(ps, 11, r.getRequestLogId());
                ps.setString(12, r.getDedupeKey());
                // Replay passes the ORIGINAL request time so a re-import does not restate history as
                // having arrived today; live ingest passes now().
                ps.setTimestamp(
                    13, Timestamp.from(r.getReceivedAt() != null ? r.getReceivedAt() : Instant.now()));
              }

              @Override
              public int getBatchSize() {
                return rows.size();
              }
            });
    int inserted = 0;
    for (int r : results) {
      // A conflict-skipped row reports 0. Some drivers report SUCCESS_NO_INFO (-2) for a batch item;
      // treat anything non-zero as inserted rather than mis-counting it as a duplicate.
      if (r != 0) {
        inserted++;
      }
    }
    return inserted;
  }

  private static void setNullable(java.sql.PreparedStatement ps, int index, String value)
      throws java.sql.SQLException {
    if (value == null) {
      ps.setNull(index, Types.VARCHAR);
    } else {
      ps.setString(index, value);
    }
  }
}
