package com.ihrms.domain.model;

import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
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
 * One employee-facing offboarding document sent for a case (table {@code offboarding_documents}, §3.6 stage
 * 2). Created PENDING when HR sends; SUBMITTED with a stored PDF when the employee signs; VERIFIED or
 * REVISION_REQUESTED (+ note) by HR. {@code hrValues} holds the per-case tokens HR typed at send.
 */
@Entity
@Table(name = "offboarding_documents")
@Getter
@Setter
@NoArgsConstructor
public class OffboardingDocument {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "caseId", nullable = false)
  private String caseId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "type", nullable = false)
  private OffboardingDocType type;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private OffboardingDocStatus status = OffboardingDocStatus.PENDING;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "sentAt", nullable = false, updatable = false)
  private Instant sentAt;

  @Column(name = "sentByUserId", nullable = false)
  private String sentByUserId;

  /** The per-case token values HR typed at send (SETTLEMENT/SEPARATION); null for EXIT_FORMALITIES. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "hrValues")
  private Map<String, Object> hrValues;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "submittedAt")
  private Instant submittedAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "verifiedAt")
  private Instant verifiedAt;

  @Column(name = "revisionNote")
  private String revisionNote;

  /** Stable per-type S3 key of the rendered PDF; null until submitted. Resubmission overwrites it. */
  @Column(name = "storageKey")
  private String storageKey;
}
