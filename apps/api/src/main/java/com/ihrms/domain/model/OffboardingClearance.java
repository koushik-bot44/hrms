package com.ihrms.domain.model;

import com.ihrms.domain.enums.ClearanceFinalStatus;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * The HR-side offboarding clearance checklist for a case (table {@code offboarding_clearance}, §3.6 stage 2).
 * One per case. {@code items} is the per-item state (yes/no + remarks, JSONB); the employee-details header is
 * prefilled from the record at render time (not stored). The employee never sees this.
 */
@Entity
@Table(name = "offboarding_clearance")
@Getter
@Setter
@NoArgsConstructor
public class OffboardingClearance {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "caseId", nullable = false)
  private String caseId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "items")
  private Map<String, Object> items;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "finalStatus", nullable = false)
  private ClearanceFinalStatus finalStatus = ClearanceFinalStatus.PENDING;

  @Column(name = "filledByUserId", nullable = false)
  private String filledByUserId;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;

  @Column(name = "storageKey")
  private String storageKey;
}
