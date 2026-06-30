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

/** Staff/operator account (table {@code users}). Auth = email + password (§6). */
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

  @Column(name = "passwordHash")
  private String passwordHash;

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
