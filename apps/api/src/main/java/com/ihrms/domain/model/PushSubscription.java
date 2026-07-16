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
 * A browser Web Push subscription (table {@code push_subscriptions}, § Web Push). One row per
 * browser/device (the {@code endpoint} is unique), owned by exactly one principal — a staff
 * {@code userId} OR a credentialed {@code employeeId} (mirrors {@link MessageRecipient}). The server
 * POSTs an encrypted, VAPID-signed payload to {@code endpoint} using {@code p256dh} + {@code auth}.
 */
@Entity
@Table(name = "push_subscriptions")
@Getter
@Setter
@NoArgsConstructor
public class PushSubscription {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** Set iff a staff User owns this subscription. */
  @Column(name = "userId")
  private String userId;

  /** Set iff a credentialed Employee owns this subscription. */
  @Column(name = "employeeId")
  private String employeeId;

  /** Denormalized tenant; null for platform roles (SUPER_ADMIN / ACCOUNTS_ADMIN). */
  @Column(name = "companyId")
  private String companyId;

  @Column(name = "endpoint", nullable = false, unique = true)
  private String endpoint;

  @Column(name = "p256dh", nullable = false)
  private String p256dh;

  @Column(name = "auth", nullable = false)
  private String auth;

  @Column(name = "userAgent")
  private String userAgent;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "lastUsedAt")
  private Instant lastUsedAt;
}
