package com.ihrms.domain.model;

import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
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

/**
 * An employee's document request routed to the team Accountant (table {@code document_requests}, §8d). The
 * routee is resolved at submit time (employee.onboardingHr → that HR's team → team.accountant) and stored
 * as {@code accountantUserId}; the Accountant lists/acts by it. SUBMITTED → IN_PROGRESS → RESOLVED, or the
 * owner cancels while SUBMITTED. Fulfilment files live in {@link RequestDocument}.
 */
@Entity
@Table(name = "document_requests")
@Getter
@Setter
@NoArgsConstructor
public class DocumentRequest {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Column(name = "companyId", nullable = false)
  private String companyId;

  /** The resolved routee — the Accountant on the employee's onboarding-HR's team. */
  @Column(name = "accountantUserId", nullable = false)
  private String accountantUserId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "requestType", nullable = false)
  private RequestType requestType;

  @Column(name = "note")
  private String note;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private RequestStatus status = RequestStatus.SUBMITTED;

  @Column(name = "resolveNote")
  private String resolveNote;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "pickedUpAt")
  private Instant pickedUpAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "resolvedAt")
  private Instant resolvedAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  public boolean isSubmitted() {
    return status == RequestStatus.SUBMITTED;
  }

  /** Open for the accountant to add files / resolve (not yet resolved or cancelled). */
  public boolean isOpen() {
    return status == RequestStatus.SUBMITTED || status == RequestStatus.IN_PROGRESS;
  }
}
