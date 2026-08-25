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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * One eSSL/ZKTeco terminal (table {@code iclock_devices}, Flyway V41), identified by the serial
 * number it sends as {@code ?SN=}. Rows are created on first contact.
 *
 * <p>A device here is RECORDED, never TRUSTED: the iClock push protocol carries no authentication
 * of any kind, so the serial is an unverified claim. Registered-serial allow-listing is Phase 1.
 *
 * <p>Every {@code Instant} carries an explicit {@code @JdbcTypeCode(TIMESTAMP_WITH_TIMEZONE)} to
 * match the {@code TIMESTAMPTZ} columns in V41. Hibernate 6 maps a bare {@code Instant} to
 * {@code TIMESTAMP_UTC}, so omitting it — or pairing it with a {@code timestamp(3)} column — fails
 * schema validation and aborts application startup.
 */
@Entity
@Table(name = "iclock_devices")
@Getter
@Setter
@NoArgsConstructor
public class IclockDevice {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "serialNumber", nullable = false)
  private String serialNumber;

  @Column(name = "name")
  private String name;

  /**
   * Denormalized, nullable tenant key with no FK — the same shape as {@code audit_logs.companyId}.
   * Nothing populates it in P0: a device announces only its serial, so it cannot be attributed to a
   * company until Phase-1 adoption.
   */
  @Column(name = "companyId")
  private String companyId;

  /** Touched on every request from this serial — the liveness signal. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "lastSeenAt")
  private Instant lastSeenAt;

  /** Touched only on GET /iclock/cdata — distinguishes a re-handshake from routine polling. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "lastHandshakeAt")
  private Instant lastHandshakeAt;

  /** {@code UNCLAIMED} until a Phase-1 operator adopts the device into a company. */
  @Column(name = "status", nullable = false)
  private String status = "UNCLAIMED";

  /** Verbatim options/info string the firmware volunteers on handshake, kept for dialect forensics. */
  @Column(name = "firmwareInfo")
  private String firmwareInfo;

  /**
   * Last {@code Stamp} the device sent on an ATTLOG push, echoed back in the handshake options block.
   * The observed firmware sends a constant {@code 9999}, so this is not a reliable high-water mark on
   * this model — duplicate history is prevented by the ack plus the dedupe index, not by this value.
   */
  @Column(name = "attlogStamp")
  private String attlogStamp;

  /** Last {@code OpStamp} sent on an OPERLOG/BIODATA push. Same caveat as {@link #attlogStamp}. */
  @Column(name = "opStamp")
  private String opStamp;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
