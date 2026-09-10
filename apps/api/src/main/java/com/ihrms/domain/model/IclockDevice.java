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

  /** {@code UNCLAIMED} until an operator adopts it, then {@code CLAIMED} (V43). */
  @Column(name = "status", nullable = false)
  private String status = "UNCLAIMED";

  /**
   * The premises this terminal guards. Null while UNCLAIMED. A DB CHECK makes the claim all-or-nothing
   * — siteId, area, direction and claimedAt are set together or not at all — because a half-claimed
   * device would promote punches with a null direction.
   */
  @Column(name = "siteId")
  private String siteId;

  /** {@code GATE} or {@code CAFETERIA}. Cafeteria punches never influence gate burst logic. */
  @Column(name = "area")
  private String area;

  /**
   * {@code IN}, {@code OUT} or {@code MIXED}. THE ONLY source of direction: on this fleet statusCode is
   * always 255 and verifyMode always 15, so the punch payload carries no directional signal at all.
   */
  @Column(name = "direction")
  private String direction;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "claimedAt")
  private Instant claimedAt;

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

  /**
   * What this terminal last said about its own face algorithm — 36.1 on the NES cafeteria readers,
   * 39.3 on the ZHM gates. Null until it has been audited once. The command funnel reads it to
   * decide whether a face template from elsewhere is even the same format.
   */
  @Column(name = "faceAlgoMajor")
  private Integer faceAlgoMajor;

  @Column(name = "faceAlgoMinor")
  private Integer faceAlgoMinor;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
