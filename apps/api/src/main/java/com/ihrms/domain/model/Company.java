package com.ihrms.domain.model;

import com.ihrms.domain.support.CompanySlug;
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

  /**
   * PERMANENT, URL-safe identifier (ARCHITECTURE.md §4) — the only human-readable name allowed in a
   * URL. Minted once from the name at creation (backfilled for pre-existing rows), globally unique
   * (case-insensitive), and NEVER changed on rename: {@code updatable = false}.
   */
  @Column(name = "slug", nullable = false, updatable = false, length = 60)
  private String slug;

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
   * Self-default the mandatory mail domain (§8) and URL slug (§4) for callers that didn't set them.
   * {@code CompaniesService} sets both explicitly on the real create path — the mail domain from the
   * (editable) input and the slug via the deduped, reserved-safe {@link CompanySlug#generate} from the
   * NAME. This fallback only fires for DIRECT persists (seeders/tests); the slug is derived from the
   * unique {@code code} (alphanumeric, unique index) so a direct save is always collision-free.
   */
  @PrePersist
  void applyDefaults() {
    if ((mailDomain == null || mailDomain.isBlank()) && code != null) {
      mailDomain = code.toLowerCase();
    }
    if (slug == null || slug.isBlank()) {
      slug = CompanySlug.slugify(code != null && !code.isBlank() ? code : name);
    }
  }
}
