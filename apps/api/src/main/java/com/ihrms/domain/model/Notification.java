package com.ihrms.domain.model;

import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Manager inbox notification (table {@code notifications}). */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "recipientUserId", nullable = false)
  private String recipientUserId;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "type", nullable = false)
  private NotificationType type;

  @Column(name = "employeeId")
  private String employeeId;

  // "read" is a SQL reserved word; globally_quoted_identifiers keeps it quoted.
  @Column(name = "read", nullable = false)
  private boolean read = false;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
