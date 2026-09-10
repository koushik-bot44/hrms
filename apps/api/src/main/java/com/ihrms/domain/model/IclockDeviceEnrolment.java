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
 * Whether one terminal holds one person's finger (table {@code iclock_device_enrolments}, V51).
 *
 * <p>Derived state, materialised on purpose. It could be reconstructed from the command log, but
 * that log records what was ATTEMPTED, and the question being asked — can this person get through
 * that door — is about what succeeded. The two differ exactly when something went wrong, which is
 * the only time anybody looks.
 */
@Entity
@Table(name = "iclock_device_enrolments")
@Getter
@Setter
@NoArgsConstructor
public class IclockDeviceEnrolment {

  /** Captured here. The terminal already holds it and nothing was sent. */
  public static final String SOURCE = "SOURCE";

  /** A propagation command is queued or in flight. */
  public static final String PENDING = "PENDING";

  /** The terminal acknowledged the template. */
  public static final String PRESENT = "PRESENT";

  /** It refused. On this fleet the interesting case is a cross-family refusal. */
  public static final String FAILED = "FAILED";

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "deviceId", nullable = false)
  private String deviceId;

  @Column(name = "pin", nullable = false)
  private String pin;

  @Column(name = "fid", nullable = false)
  private int fid;

  /** 1 = fingerprint, 2 = face. */
  @Column(name = "bioType", nullable = false)
  private int bioType = 1;

  /** Which template this device is believed to hold, so a re-enrolment is distinguishable. */
  @Column(name = "fingerprint")
  private String fingerprint;

  @Column(name = "status", nullable = false)
  private String status = PENDING;

  @Column(name = "commandId")
  private String commandId;

  @Column(name = "failureReason")
  private String failureReason;

  @CreationTimestamp
  @Column(name = "createdAt", nullable = false, updatable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant createdAt;

  @UpdateTimestamp
  @Column(name = "updatedAt", nullable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant updatedAt;
}
