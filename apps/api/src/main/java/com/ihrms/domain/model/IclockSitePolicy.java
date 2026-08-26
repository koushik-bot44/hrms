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
 * Per-building policy settings (table {@code iclock_site_policies}, Flyway V46).
 *
 * <p>Named for POLICY rather than for the alert that first needed it. P2's engine resolves shift start,
 * late grace, allowed break, weekly-off days and the payroll cycle the same way, and they belong in
 * this row — a table called "break alert settings" would have guaranteed a second, competing one.
 *
 * <p>Every column is NOT NULL, so a row that exists is complete and resolution never has to ask what a
 * null means at this level. Absence of a row is the only "inherit", and the resolver answers it with
 * code defaults.
 */
@Entity
@Table(name = "iclock_site_policies")
@Getter
@Setter
@NoArgsConstructor
public class IclockSitePolicy {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "siteId", nullable = false)
  private String siteId;

  /** Away longer than this shows the person in the "exceeding break" strip. */
  @Column(name = "breakAlertMin", nullable = false)
  private int breakAlertMin = 30;

  /**
   * Away longer than this retires the alert — a continuous absence that long is presumed a departure,
   * and the person simply stays in "Left".
   */
  @Column(name = "breakAlertMaxMin", nullable = false)
  private int breakAlertMaxMin = 120;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
