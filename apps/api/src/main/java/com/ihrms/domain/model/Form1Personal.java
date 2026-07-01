package com.ihrms.domain.model;

import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.support.CuidId;
import com.ihrms.domain.support.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Form 1 — Personal Details (table {@code form1_personal}; one per employee). Non-sensitive scalars
 * and the educational / working-experience / family / character-reference child rows live in
 * {@code data} (JSONB); {@code offeredCtc} is encrypted at rest (§6), and
 * {@code workingExperiences[].salaryCtc} is encrypted within {@code data} by the service.
 */
@Entity
@Table(name = "form1_personal")
@Getter
@Setter
@NoArgsConstructor
public class Form1Personal {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data")
  private Map<String, Object> data;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "offeredCtc")
  private String offeredCtc;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "status", nullable = false)
  private SectionStatus status = SectionStatus.DRAFT;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
