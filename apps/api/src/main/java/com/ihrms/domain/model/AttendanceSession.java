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
 * One attendance session (table {@code attendance_sessions}, §8a): a clock-in with an optional clock-out.
 * {@code clockOutAt == null} means the session is still OPEN. Instants are UTC ({@code timestamptz}); the
 * app renders + groups them in Asia/Kolkata. The server sets the timestamps — a client time is never trusted.
 */
@Entity
@Table(name = "attendance_sessions")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceSession {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  /** Denormalized tenant key — every attendance query filters by it (§6). */
  @Column(name = "companyId", nullable = false)
  private String companyId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "clockInAt", nullable = false)
  private Instant clockInAt;

  /** Null while the session is open (the employee has not clocked out). */
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "clockOutAt")
  private Instant clockOutAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;

  public boolean isOpen() {
    return clockOutAt == null;
  }
}
