package com.ihrms.domain.model;

import com.ihrms.domain.enums.UserRole;
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
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** Staff/operator account (table {@code users}). Auth = full name + email + OTP (§6). */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "email", nullable = false)
  private String email;

  /** Internal-mail local part; the mailbox address {@code mailLocalPart@domain} == the login email (§8). */
  @Column(name = "mailLocalPart")
  private String mailLocalPart;

  @Column(name = "name", nullable = false)
  private String name;

  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "role", nullable = false)
  private UserRole role;

  @Column(name = "companyId")
  private String companyId; // null for SUPER_ADMIN

  @Column(name = "teamId")
  private String teamId; // home team for HR/Manager

  /** Retained but dormant (break-glass); the login factor is now the emailed OTP below (§6). */
  @Column(name = "passwordHash")
  private String passwordHash;

  @Column(name = "otpHash")
  private String otpHash;

  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "otpExpiresAt")
  private Instant otpExpiresAt;

  @Column(name = "status", nullable = false)
  private String status = "ACTIVE";

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;
}
