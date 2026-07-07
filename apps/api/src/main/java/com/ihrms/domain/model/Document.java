package com.ihrms.domain.model;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
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
 * A Form 4 uploaded file under an employee record (table {@code documents}). {@code docType} is the
 * slot; {@code groupIndex} (1..4) distinguishes the per-employment groups (offer/hike/relieving),
 * otherwise null. The raw {@code storageKey} is never exposed to clients (§6).
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "docType", nullable = false)
  private DocumentType docType;

  /** 1..4 for the per-employment document groups; null for single-slot documents. */
  @Column(name = "groupIndex")
  private Integer groupIndex;

  @Column(name = "fileName", nullable = false)
  private String fileName;

  @Column(name = "storageKey", nullable = false)
  private String storageKey;

  @Column(name = "mimeType", nullable = false)
  private String mimeType;

  @Column(name = "sha256")
  private String sha256;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private DocumentStatus status = DocumentStatus.PENDING;

  /** HR's note when this document is sent back for re-upload (§3.3); null otherwise. */
  @Column(name = "revisionNote")
  private String revisionNote;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "revisionRequestedAt")
  private Instant revisionRequestedAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "uploadedAt", nullable = false, updatable = false)
  private Instant uploadedAt;
}
