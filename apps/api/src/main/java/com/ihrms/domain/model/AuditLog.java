package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit trail (table {@code audit_logs}, §7). {@code companyId} is the
 * partition key (no FK — logs outlive entities). Updates/deletes are blocked at the DB
 * (a trigger in Flyway V2) and not exposed by the repository.
 */
@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "companyId")
  private String companyId;

  @Column(name = "actorType", nullable = false)
  private String actorType; // USER | EMPLOYEE | SYSTEM

  @Column(name = "actorId")
  private String actorId;

  @Column(name = "action", nullable = false)
  private String action;

  @Column(name = "targetType")
  private String targetType;

  @Column(name = "targetId")
  private String targetId;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata")
  private Map<String, Object> metadata;

  @Column(name = "ipAddress")
  private String ipAddress;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
