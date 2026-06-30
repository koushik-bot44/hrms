package com.ihrms.domain.model;

import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** HR→Manager approval request for an employee (table {@code approval_requests}). */
@Entity
@Table(name = "approval_requests")
@Getter
@Setter
@NoArgsConstructor
public class ApprovalRequest {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Column(name = "hrUserId", nullable = false)
  private String hrUserId;

  @Column(name = "managerUserId", nullable = false)
  private String managerUserId;

  @Column(name = "teamId", nullable = false)
  private String teamId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private ApprovalStatus status = ApprovalStatus.PENDING;

  @Column(name = "note")
  private String note;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "submittedAt", nullable = false, updatable = false)
  private Instant submittedAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "decidedAt")
  private Instant decidedAt;
}
