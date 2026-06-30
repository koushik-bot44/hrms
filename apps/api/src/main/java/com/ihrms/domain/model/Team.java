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
 * A team within a company (table {@code teams}). The single nullable hrUserId/managerUserId
 * columns structurally enforce "exactly one HR + one Manager", assignable after creation.
 */
@Entity
@Table(name = "teams")
@Getter
@Setter
@NoArgsConstructor
public class Team {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "companyId", nullable = false)
  private String companyId;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "hrUserId")
  private String hrUserId;

  @Column(name = "managerUserId")
  private String managerUserId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
