package com.ihrms.domain.model;

import com.ihrms.domain.enums.AgreementStatus;
import com.ihrms.domain.enums.EmployeeAgreementType;
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
 * One standard company agreement sent to one employee post-approval (table {@code employee_agreements}).
 * Created PENDING when HR sends the pack; flips to COMPLETED with a stored PDF {@code storageKey} when the
 * employee signs and submits. {@code sentByUserId} is the sending HR — it feeds {{HR_NAME}} on the NDA and
 * receives the completion notification. Unique per (employeeId, type).
 */
@Entity
@Table(name = "employee_agreements")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeAgreement {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "type", nullable = false)
  private EmployeeAgreementType type;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private AgreementStatus status = AgreementStatus.PENDING;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "sentAt", nullable = false, updatable = false)
  private Instant sentAt;

  @Column(name = "sentByUserId", nullable = false)
  private String sentByUserId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "completedAt")
  private Instant completedAt;

  /** Server-side S3 key of the rendered PDF; null until completed. */
  @Column(name = "storageKey")
  private String storageKey;
}
