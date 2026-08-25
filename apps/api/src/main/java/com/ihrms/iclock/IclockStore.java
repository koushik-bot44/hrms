package com.ihrms.iclock;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockRequestLog;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockRequestLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional primitives for ADMS ingest. Deliberately a separate bean from
 * {@link IclockService}: every method here runs in its OWN transaction
 * ({@code REQUIRES_NEW}), and the retry/fallback logic that calls them must therefore live in a
 * different bean — a self-invocation would bypass the proxy and silently run in the caller's
 * transaction, defeating the whole design.
 *
 * <p>Independent transactions are what make the "raw capture is the commit point" rule work: the
 * request-log row is committed before the handler runs, so a later failure to parse or to insert
 * punches cannot roll it back, and the device can still be answered "OK" with the raw row kept as
 * the replay source.
 */
@Service
public class IclockStore {

  private final IclockDeviceRepository devices;
  private final IclockRawPunchRepository punches;
  private final IclockRequestLogRepository logs;

  public IclockStore(
      IclockDeviceRepository devices,
      IclockRawPunchRepository punches,
      IclockRequestLogRepository logs) {
    this.devices = devices;
    this.punches = punches;
    this.logs = logs;
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
   * Creates the device on first contact, or touches its liveness columns. Throws
   * {@code DataIntegrityViolationException} when it loses the unique-index race on
   * {@code serialNumber} — the caller recovers by re-reading in a fresh transaction, which is only
   * possible because this transaction is its own.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String upsertDevice(String serialNumber, boolean handshake, String firmwareInfo) {
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
    return devices.saveAndFlush(device).getId();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
  public Optional<String> findDeviceIdBySerial(String serialNumber) {
    return devices.findBySerialNumber(serialNumber).map(IclockDevice::getId);
  }

  /** Inserts a whole ATTLOG batch. All-or-nothing within this transaction only. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int insertPunches(List<IclockRawPunch> rows) {
    if (rows.isEmpty()) {
      return 0;
    }
    return punches.saveAll(rows).size();
  }
}
