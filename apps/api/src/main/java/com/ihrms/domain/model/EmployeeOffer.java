package com.ihrms.domain.model;

import com.ihrms.domain.enums.OfferStatus;
import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The Offer Letter that opens an employee's onboarding (table {@code employee_offers}, §3.2). Created SENT
 * when HR invites a NEW employee (existing in-flight employees have no row and are never gated); the invited
 * employee reads + signs + ACCEPTS it before any onboarding form unlocks. {@code terms} is a JSONB snapshot of
 * what HR entered ({@code joiningDate}, {@code salary}, {@code location}, {@code offerDate}) — rendered into
 * the PDF only, never written into Form data. Company-issued: the employee does not fill it, only accepts.
 */
@Entity
@Table(name = "employee_offers")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeOffer {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "terms", nullable = false)
  private Map<String, Object> terms;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private OfferStatus status = OfferStatus.SENT;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "acceptedAt")
  private Instant acceptedAt;

  /** Stable S3 key of the accepted PDF; null until accepted. */
  @Column(name = "storageKey")
  private String storageKey;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
