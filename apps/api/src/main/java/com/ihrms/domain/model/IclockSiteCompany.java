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
import org.hibernate.type.SqlTypes;

/**
 * Membership of a company in a site (table {@code iclock_site_companies}, Flyway V43).
 *
 * <p>Decision D2: a company belongs to <b>exactly one</b> site, enforced by a unique index on
 * {@code companyId} alone. That single constraint is what collapses a PIN's uniqueness scope from a
 * set-valued two-hop join into a scalar, making {@code UNIQUE(siteId, pin)} on
 * {@code iclock_employee_pins} enforceable by the database.
 *
 * <p>Relaxing this to a true many-to-many later would make that constraint unsatisfiable — it is a
 * schema change, not a configuration change.
 */
@Entity
@Table(name = "iclock_site_companies")
@Getter
@Setter
@NoArgsConstructor
public class IclockSiteCompany {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "siteId", nullable = false)
  private String siteId;

  /** Uniquely indexed on its own: one site per company (D2). */
  @Column(name = "companyId", nullable = false)
  private String companyId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
