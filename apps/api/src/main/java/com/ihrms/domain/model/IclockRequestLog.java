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
 * Verbatim capture of one {@code /iclock} request (table {@code iclock_request_logs}, Flyway V41) —
 * the diagnostic record we read to learn what this firmware actually sends, since its dialect is
 * unknown in P0.
 *
 * <p>The row is written BEFORE the handler runs and is the ingest commit point: once it is
 * committed, every downstream failure is swallowed and the device still receives "OK", because this
 * row alone is enough to replay from. {@link #responseStatus} and {@link #durationMs} are filled in
 * by a best-effort update afterwards.
 *
 * <p>{@link #headers} is REDACTED for sensitive names before storage. The capturing filter runs
 * ahead of Spring Security and therefore sees requests security would have rejected — including any
 * that carry a live bearer token.
 */
@Entity
@Table(name = "iclock_request_logs")
@Getter
@Setter
@NoArgsConstructor
public class IclockRequestLog {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** From {@code ?SN=}; null when the device did not send one (itself worth knowing). */
  @Column(name = "serialNumber")
  private String serialNumber;

  @Column(name = "method", nullable = false)
  private String method;

  @Column(name = "path", nullable = false)
  private String path;

  @Column(name = "queryString")
  private String queryString;

  @Column(name = "headers")
  private String headers;

  @Column(name = "body")
  private String body;

  @Column(name = "bodyBytes", nullable = false)
  private int bodyBytes;

  /** True when the body exceeded {@code app.iclock.max-body-bytes} and was cut short. */
  @Column(name = "bodyTruncated", nullable = false)
  private boolean bodyTruncated;

  /** True when NUL bytes were stripped so the payload could be stored as Postgres TEXT. */
  @Column(name = "sanitized", nullable = false)
  private boolean sanitized;

  @Column(name = "remoteAddr")
  private String remoteAddr;

  /** The {@code ?table=} parameter (ATTLOG / OPERLOG / ...). Named to avoid the SQL reserved word. */
  @Column(name = "tableName")
  private String tableName;

  @Column(name = "responseStatus")
  private Integer responseStatus;

  @Column(name = "durationMs")
  private Integer durationMs;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP_WITH_TIMEZONE)
  @Column(name = "receivedAt", nullable = false, updatable = false)
  private Instant receivedAt;
}
