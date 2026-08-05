package com.ihrms.domain.model;

import com.ihrms.domain.enums.RequestType;
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
import org.hibernate.type.SqlTypes;

/**
 * One company-issued offboarding letter for a case (table {@code offboarding_letters}, §3.6 stage 3). Unlike
 * the employee-facing {@link OffboardingDocument}, a letter is HR-generated from a single-source template (the
 * employee never fills or signs it): the row exists once the letter is ISSUED. {@code hrValues} holds the
 * tokens HR typed at issue (dates, designation, tenure, and — Experience — the gender). Re-issue overwrites
 * the row's {@code hrValues}, {@code storageKey} (stable per case+type) and {@code issuedAt}.
 */
@Entity
@Table(name = "offboarding_letters")
@Getter
@Setter
@NoArgsConstructor
public class OffboardingLetter {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "caseId", nullable = false)
  private String caseId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "type", nullable = false)
  private RequestType type;

  /** The per-case token values HR typed at issue (JSONB). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "hrValues")
  private Map<String, Object> hrValues;

  /** Stable per-(case,type) S3 key of the rendered PDF; re-issue overwrites the same key. */
  @Column(name = "storageKey", nullable = false)
  private String storageKey;

  @Column(name = "issuedByUserId", nullable = false)
  private String issuedByUserId;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "issuedAt", nullable = false)
  private Instant issuedAt;
}
