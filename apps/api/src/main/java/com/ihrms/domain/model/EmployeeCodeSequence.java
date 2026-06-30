package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * Per-company monotonic counter for employee IDs (table {@code employee_code_sequences}, §5).
 * Allocated atomically by {@code EmployeeCodeService} via an upsert-returning; this entity
 * exists for the schema + reads.
 */
@Entity
@Table(name = "employee_code_sequences")
@Getter
@Setter
@NoArgsConstructor
public class EmployeeCodeSequence {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "companyId", nullable = false)
  private String companyId;

  @Column(name = "lastSeq", nullable = false)
  private int lastSeq = 0;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
