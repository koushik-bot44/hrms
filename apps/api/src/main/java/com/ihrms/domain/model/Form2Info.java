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
 * Form 2 — Employee Info (table {@code form2_info}; one per employee). Non-sensitive scalars live in
 * {@code data} (JSONB); {@code panNumber} and {@code axisAccountNumber} are encrypted at rest (§6).
 * {@code sparkId} is HR/admin-set. The employeeId printed on Form 2 is the minted employee code (null
 * until Manager approval) — not stored here.
 */
@Entity
@Table(name = "form2_info")
@Getter
@Setter
@NoArgsConstructor
public class Form2Info {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data")
  private Map<String, Object> data;

  @Column(name = "sparkId")
  private String sparkId;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "panNumber")
  private String panNumber;

  @Convert(converter = EncryptedStringConverter.class)
  @Column(name = "axisAccountNumber")
  private String axisAccountNumber;

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
