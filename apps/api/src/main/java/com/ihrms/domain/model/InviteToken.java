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
 * A signed, expiring invite that authorizes the employee onboarding door (table {@code employee_invite_tokens},
 * §3.2/§6). The candidate reaches the OTP flow ONLY via the link HR emailed; that link carries the RAW token
 * (never stored) whose SHA-256 hex is {@link #tokenHash} here. One ACTIVE token per employee — issuing a new one
 * (invite / email-change re-invite / HR resend) sets {@link #revokedAt} on the prior. A token authorizes the
 * door only while it is not revoked, not past {@link #expiresAt} (now + 7 days), and the employee is still in an
 * active onboarding status. {@link #usedAt} records the first successful verify but does NOT end validity.
 */
@Entity
@Table(name = "employee_invite_tokens")
@Getter
@Setter
@NoArgsConstructor
public class InviteToken {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "employeeId", nullable = false)
  private String employeeId;

  /** SHA-256 hex of the opaque token — the raw value is emailed once and never persisted. */
  @Column(name = "tokenHash", nullable = false)
  private String tokenHash;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "expiresAt", nullable = false)
  private Instant expiresAt;

  /** First successful OTP verify (informational; does NOT gate validity). */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "usedAt")
  private Instant usedAt;

  /** Set when superseded by a newer token (the one-active-token invariant). */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "revokedAt")
  private Instant revokedAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
