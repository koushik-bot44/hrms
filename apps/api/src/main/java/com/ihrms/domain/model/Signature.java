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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * The single captured e-signature (table {@code signatures}; one per employee). A drawn or typed
 * image is stored as an upload ({@code storageKey}); it is stamped onto Forms 1 & 2 and the merged
 * PDF. {@code signedAt} records when the employee signed at final submit.
 */
@Entity
@Table(name = "signatures")
@Getter
@Setter
@NoArgsConstructor
public class Signature {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  @Column(name = "storageKey", nullable = false)
  private String storageKey;

  /** How the signature was captured: {@code DRAWN} or {@code TYPED}. */
  @Column(name = "type", nullable = false)
  private String type = "DRAWN";

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "signedAt", nullable = false)
  private Instant signedAt;
}
