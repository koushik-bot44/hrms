package com.ihrms.domain.enums;

/** Staff roles (ARCHITECTURE.md §2/§4). Persisted to the PG enum type {@code "UserRole"}. */
public enum UserRole {
  SUPER_ADMIN,
  /** Top-level, cross-company READ-ONLY viewer of approved employees (§2/§6); singleton. */
  ACCOUNTS_ADMIN,
  /** Cross-platform READ-ONLY, AGGREGATES-ONLY overview role (§2/§6); no PII, no writes; singleton. */
  HIERARCHY,
  COMPANY_ADMIN,
  HR,
  MANAGER,
  /** Team-scoped READ-ONLY viewer of its own team's approved employees (§2/§6). */
  ACCOUNTANT
}
