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
 * One fingerprint, as a terminal serialised it (table {@code iclock_biometric_templates}, V51).
 *
 * <p>Arrives unasked, inside an OPERLOG push, when somebody enrols at a device. Kept so the same
 * finger can be pushed to the other terminals in the building without the person walking to each
 * one.
 */
@Entity
@Table(name = "iclock_biometric_templates")
@Getter
@Setter
@NoArgsConstructor
public class IclockBiometricTemplate {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "siteId", nullable = false)
  private String siteId;

  @Column(name = "pin", nullable = false)
  private String pin;

  @Column(name = "personId")
  private String personId;

  /** The device's own finger index, 0-9. A face carries none and is stored at 0. */
  @Column(name = "fid", nullable = false)
  private int fid;

  /** 1 = fingerprint, 2 = face, following the device's own Type numbering. */
  @Column(name = "bioType", nullable = false)
  private int bioType = 1;

  /** Base64, exactly as received. */
  @Column(name = "template", nullable = false)
  private String template;

  /** The decoded byte count the DEVICE reported, kept verbatim because it is what gets sent back. */
  @Column(name = "size", nullable = false)
  private int size;

  @Column(name = "valid", nullable = false)
  private int valid = 1;

  /** Cheap identity, so propagation can be idempotent without comparing kilobytes of base64. */
  @Column(name = "fingerprint", nullable = false)
  private String fingerprint;

  /** Which terminal captured it — the provenance that makes a cross-family failure readable. */
  @Column(name = "sourceDeviceId")
  private String sourceDeviceId;

  @Column(name = "capturedAt", nullable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant capturedAt = Instant.now();

  @CreationTimestamp
  @Column(name = "createdAt", nullable = false, updatable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updatedAt", nullable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant updatedAt;
}
