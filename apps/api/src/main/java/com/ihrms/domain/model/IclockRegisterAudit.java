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
import org.hibernate.type.SqlTypes;

/**
 * One comparison of a terminal's own register against the roster (table {@code
 * iclock_register_audits}, V52).
 *
 * <p>Kept as history rather than overwritten, because an audit is evidence about a moment and the
 * question later is usually whether something was already true last week.
 */
@Entity
@Table(name = "iclock_register_audits")
@Getter
@Setter
@NoArgsConstructor
public class IclockRegisterAudit {

  /** The query has been queued; the terminal has not finished answering. */
  public static final String REQUESTED = "REQUESTED";

  /** The dump arrived and has been diffed. */
  public static final String RECEIVED = "RECEIVED";

  public static final String FAILED = "FAILED";

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "deviceId", nullable = false)
  private String deviceId;

  @Column(name = "pinsOnDevice", nullable = false)
  private int pinsOnDevice;

  @Column(name = "clean", nullable = false)
  private int clean;

  @Column(name = "stale", nullable = false)
  private int stale;

  @Column(name = "unknown", nullable = false)
  private int unknown;

  @Column(name = "templateGaps", nullable = false)
  private int templateGaps;

  @Column(name = "status", nullable = false)
  private String status = REQUESTED;

  @Column(name = "commandId")
  private String commandId;

  @Column(name = "requestedBy", nullable = false)
  private String requestedBy;

  @Column(name = "requestedAt", nullable = false)
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant requestedAt = Instant.now();

  @Column(name = "completedAt")
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  private Instant completedAt;
}
