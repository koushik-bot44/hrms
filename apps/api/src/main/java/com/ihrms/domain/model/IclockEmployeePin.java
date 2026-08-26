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
 * Maps a terminal enrolment number to an employee (table {@code iclock_employee_pins}, Flyway V43).
 *
 * <p><b>Pins are stored CANONICAL</b> — leading zeros stripped (decision D3). This is not hypothetical:
 * the live fleet sends {@code 000261}, {@code 000262} and {@code 02919} zero-padded, so an operator
 * importing {@code 261} would never match a punch, and the failure would be indistinguishable from an
 * unenrolled finger. Ingest canonicalises identically before resolving; {@code iclock_raw_punches.rawLine}
 * keeps the device's original text verbatim.
 *
 * <p>{@link #siteId} is DENORMALISED from employee → company → site at write time. It exists solely so
 * that {@code UNIQUE(siteId, pin)} can enforce the rule "a pin resolves to exactly one employee within a
 * site". Any operation that moves a company between sites MUST recompute it in the same transaction, or
 * the constraint silently guards a stale scope.
 */
@Entity
@Table(name = "iclock_employee_pins")
@Getter
@Setter
@NoArgsConstructor
public class IclockEmployeePin {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** Uniquely indexed: one pin per employee. */
  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  /** Canonical form: digits, no leading zeros. DB CHECK enforces {@code ^[1-9][0-9]{0,19}$}. */
  @Column(name = "pin", nullable = false)
  private String pin;

  /** Denormalised uniqueness scope — see the class javadoc. Recompute on any site re-link. */
  @Column(name = "siteId", nullable = false)
  private String siteId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
