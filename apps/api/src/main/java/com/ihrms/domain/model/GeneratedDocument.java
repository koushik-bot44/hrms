package com.ihrms.domain.model;

import com.ihrms.domain.enums.GeneratedDocumentKind;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A generated onboarding PDF (table {@code generated_documents}). One row per
 * {@link GeneratedDocumentKind} per employee (unique); the bytes live in storage under
 * {@code storageKey} and are reached only via short-lived presigned URLs (§6). Regenerated on edit
 * and again when Manager approval mints the employee ID.
 */
@Entity
@Table(name = "generated_documents")
@Getter
@Setter
@NoArgsConstructor
public class GeneratedDocument {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "kind", nullable = false)
  private GeneratedDocumentKind kind;

  @Column(name = "fileName", nullable = false)
  private String fileName;

  @Column(name = "storageKey", nullable = false)
  private String storageKey;

  @Column(name = "sha256")
  private String sha256;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "generatedAt", nullable = false)
  private Instant generatedAt;
}
