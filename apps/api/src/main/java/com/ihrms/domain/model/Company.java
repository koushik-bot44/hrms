package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/** A tenant company (table {@code companies}). {@code code} is the employee-ID mnemonic. */
@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
public class Company {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "code", nullable = false)
  private String code;

  /** The company's internal-mail domain (e.g. {@code anvicorp}); unique across companies (§8). */
  @Column(name = "mailDomain", nullable = false)
  private String mailDomain;

  /** {@code ACTIVE} | {@code SUSPENDED} | {@code DELETED} (archived; free text, not a domain enum). */
  @Column(name = "status", nullable = false)
  private String status = "ACTIVE";

  /** Set when archived (soft-delete), cleared on restore. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deletedAt")
  private Instant deletedAt;

  /** The Super Admin who archived it (→ users.id); null when active. */
  @Column(name = "deletedByUserId")
  private String deletedByUserId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;

  /**
   * Default the mandatory mail domain (§8) from {@code code} when the caller didn't set one — mirrors
   * the V12 backfill so a company is always mailable. {@code CompaniesService} still sets/normalizes it
   * explicitly (Super Admin can edit it at creation).
   */
  @PrePersist
  void defaultMailDomain() {
    if ((mailDomain == null || mailDomain.isBlank()) && code != null) {
      mailDomain = code.toLowerCase();
    }
  }
}
