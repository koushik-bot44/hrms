package com.ihrms.domain.model;

import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.enums.LeaveType;
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
 * Employee leave request routed to the team Manager (table {@code leave_requests}, §8b). The approver is
 * resolved at submit time (employee.onboardingHr → that HR's team → team.manager) and stored as
 * {@code managerUserId}; the Manager lists/decides by it. No leave balances in v1.
 */
@Entity
@Table(name = "leave_requests")
@Getter
@Setter
@NoArgsConstructor
public class LeaveRequest {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Column(name = "companyId", nullable = false)
  private String companyId;

  /** The resolved approver — the Manager on the employee's onboarding-HR's team. */
  @Column(name = "managerUserId", nullable = false)
  private String managerUserId;

  @Column(name = "startDate", nullable = false)
  private LocalDate startDate;

  @Column(name = "endDate", nullable = false)
  private LocalDate endDate;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "leaveType", nullable = false)
  private LeaveType leaveType;

  @Column(name = "reason", nullable = false)
  private String reason;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private LeaveStatus status = LeaveStatus.PENDING;

  @Column(name = "decisionNote")
  private String decisionNote;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "decidedAt")
  private Instant decidedAt;

  @Column(name = "decidedByUserId")
  private String decidedByUserId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  public boolean isPending() {
    return status == LeaveStatus.PENDING;
  }
}
