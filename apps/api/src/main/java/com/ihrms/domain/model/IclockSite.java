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
 * A physical premises guarded by a set of terminals (table {@code iclock_sites}, Flyway V43).
 *
 * <p>A site is the scope in which a device PIN must be unambiguous. Because a company belongs to
 * exactly one site (decision D2, enforced by a unique index on {@code iclock_site_companies.companyId}),
 * an employee has exactly one site — which is what lets the PIN rule be a real database constraint
 * rather than a periodic report.
 *
 * <p>{@link #timezone} is the IANA zone used to interpret the terminal's offset-less wall-clock string.
 * It is defaulted rather than required because the whole fleet is currently in one zone; the column
 * exists so the first out-of-zone site is a data change, not a migration.
 */
@Entity
@Table(name = "iclock_sites")
@Getter
@Setter
@NoArgsConstructor
public class IclockSite {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "name", nullable = false)
  private String name;

  /** IANA zone id, e.g. {@code Asia/Kolkata}. Used to interpret device wall-clock stamps. */
  @Column(name = "timezone", nullable = false)
  private String timezone = "Asia/Kolkata";

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
