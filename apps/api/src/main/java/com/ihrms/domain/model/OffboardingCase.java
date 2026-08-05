package com.ihrms.domain.model;

import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An offboarding case for an employee (table {@code offboarding_cases}, §Offboarding stage 1). Created
 * PENDING_APPROVAL by HR; the HIERARCHY role approves/rejects; HR may cancel pre-completion. At most one
 * non-terminal case (PENDING_APPROVAL or APPROVED) per employee. Employee.status is not touched in stage 1.
 */
@Entity
@Table(name = "offboarding_cases")
@Getter
@Setter
@NoArgsConstructor
public class OffboardingCase {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private OffboardingStatus status = OffboardingStatus.PENDING_APPROVAL;

  @Column(name = "reason", nullable = false)
  private String reason;

  @JdbcTypeCode(SqlTypes.DATE)
  @Column(name = "lastWorkingDay", nullable = false)
  private LocalDate lastWorkingDay;

  @Column(name = "initiatedByUserId", nullable = false)
  private String initiatedByUserId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "initiatedAt", nullable = false, updatable = false)
  private Instant initiatedAt;

  @Column(name = "decidedByUserId")
  private String decidedByUserId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "decidedAt")
  private Instant decidedAt;

  @Column(name = "decisionNote")
  private String decisionNote;

  @Column(name = "cancelledByUserId")
  private String cancelledByUserId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "cancelledAt")
  private Instant cancelledAt;

  @Column(name = "cancelNote")
  private String cancelNote;

  // --- HR completion (§3.6 stage 3); null until COMPLETED ---
  @Column(name = "completedByUserId")
  private String completedByUserId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "completedAt")
  private Instant completedAt;

  @Column(name = "completionNote")
  private String completionNote;
}
