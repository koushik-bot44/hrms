package com.ihrms.domain.enums;

/** Staff roles (ARCHITECTURE.md §2/§4). Persisted to the PG enum type {@code "UserRole"}. */
public enum UserRole {
  SUPER_ADMIN,
  /** Top-level, cross-company READ-ONLY viewer of approved employees (§2/§6); singleton. */
  ACCOUNTANT,
  COMPANY_ADMIN,
  HR,
  MANAGER
}
