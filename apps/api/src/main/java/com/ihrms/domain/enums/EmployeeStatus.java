package com.ihrms.domain.enums;

/** Employee onboarding lifecycle (§4). PG enum type {@code "EmployeeStatus"}. */
public enum EmployeeStatus {
  INVITED,
  IN_PROGRESS,
  SUBMITTED,
  HR_VERIFIED,
  APPROVED,
  REJECTED
}
