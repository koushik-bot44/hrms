package com.ihrms.domain.enums;

/** Staff roles (ARCHITECTURE.md §2/§4). Persisted to the PG enum type {@code "UserRole"}. */
public enum UserRole {
  SUPER_ADMIN,
  COMPANY_ADMIN,
  HR,
  MANAGER
}
