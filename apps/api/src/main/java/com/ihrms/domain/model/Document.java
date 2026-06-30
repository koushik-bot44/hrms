package com.ihrms.domain.model;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.SectionKey;
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

/** Uploaded file under an employee record (table {@code documents}); storageKey never exposed. */
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
  @Column(name = "sectionKey", nullable = false)
  private SectionKey sectionKey;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "docType", nullable = false)
  private DocumentType docType;

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
  private DocumentStatus status = DocumentStatus.UPLOADED;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "uploadedAt", nullable = false, updatable = false)
  private Instant uploadedAt;
}
