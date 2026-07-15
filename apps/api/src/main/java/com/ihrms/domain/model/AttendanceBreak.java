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
 * A break within an attendance session (table {@code attendance_breaks}, §8a v2). Excluded from worked
 * hours. {@code breakEndAt == null} means the break is still OPEN. One open break per session (partial
 * unique index). Instants are UTC; the server sets them.
 */
@Entity
@Table(name = "attendance_breaks")
@Getter
@Setter
@NoArgsConstructor
public class AttendanceBreak {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "sessionId", nullable = false)
  private String sessionId;

  /** Denormalized tenant key (§6). */
  @Column(name = "companyId", nullable = false)
  private String companyId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "breakStartAt", nullable = false)
  private Instant breakStartAt;

  /** Null while the break is open (the employee has not ended it). */
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "breakEndAt")
  private Instant breakEndAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  public boolean isOpen() {
    return breakEndAt == null;
  }
}
