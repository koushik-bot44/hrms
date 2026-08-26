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
 * Records that a raw punch was absorbed into a burst (table {@code iclock_punch_members}, Flyway V43).
 *
 * <p>Membership is explicit rather than inferred, for two reasons. It makes "has this raw punch already
 * been promoted?" a single indexed lookup — including for followers, which produce no effective row of
 * their own and would otherwise be re-processed on every run. And it keeps {@code iclock_raw_punches}
 * untouched, so raw stays a pure append-only record of what the device said.
 *
 * <p>The unique index on {@link #rawPunchId} IS the idempotency contract: re-running promotion or the
 * backfill inserts nothing and updates nothing.
 */
@Entity
@Table(name = "iclock_punch_members")
@Getter
@Setter
@NoArgsConstructor
public class IclockPunchMember {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "punchId", nullable = false)
  private String punchId;

  /** Uniquely indexed — a raw punch belongs to at most one burst. */
  @Column(name = "rawPunchId", nullable = false)
  private String rawPunchId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
