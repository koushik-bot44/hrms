package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One ATTLOG line pushed by a terminal (table {@code iclock_raw_punches}, Flyway V41).
 *
 * <p>{@link #rawLine} is the authoritative record. The parsed columns are nullable convenience
 * projections: firmware varies in column count and order, so a short or unexpected line leaves them
 * NULL rather than failing the ingest. P0 derives nothing from these rows — there is no
 * PIN-to-employee mapping and no attendance session is created.
 *
 * <p>{@link #punchedAtRaw} is kept as text on purpose. The device sends an offset-less local
 * wall-clock string from a drifting RTC; choosing a timezone for it is a policy decision reserved
 * for Phase 1.
 */
@Entity
@Table(name = "iclock_raw_punches")
@Getter
@Setter
@NoArgsConstructor
public class IclockRawPunch {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /**
   * Best-effort link to {@code iclock_devices}, nullable by design: if the device upsert loses a
   * race or fails outright we still store the punch with a NULL deviceId rather than dropping it.
   * {@link #serialNumber} is the durable identity.
   */
  @Column(name = "deviceId")
  private String deviceId;

  @Column(name = "serialNumber", nullable = false)
  private String serialNumber;

  /** The device enrolment number. Opaque in P0 — no FK to employees; mapping is Phase 1. */
  @Column(name = "devicePin")
  private String devicePin;

  @Column(name = "punchedAtRaw")
  private String punchedAtRaw;

  @Column(name = "statusCode")
  private String statusCode;

  @Column(name = "verifyMode")
  private String verifyMode;

  @Column(name = "workCode")
  private String workCode;

  /** The whole TAB-separated line, verbatim. Never null — the service guards this before save. */
  @Column(name = "rawLine", nullable = false)
  private String rawLine = "";

  /** 1-based position of this line within its request body, so a batch can be reconstructed in order. */
  @Column(name = "lineNumber", nullable = false)
  private int lineNumber;

  /** Correlates to {@code iclock_request_logs.id}. No FK — an orphan beats losing a punch. */
  @Column(name = "requestLogId")
  private String requestLogId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "receivedAt", nullable = false, updatable = false)
  private Instant receivedAt;
}
