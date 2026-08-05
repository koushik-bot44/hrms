package com.ihrms.domain.enums;

/** Employee onboarding lifecycle (§4). PG enum type {@code "EmployeeStatus"}. */
public enum EmployeeStatus {
  INVITED,
  IN_PROGRESS,
  SUBMITTED,
  /** HR sent one or more items back for revision (§3.3); the employee is fixing the flagged items. */
  REVISION_REQUESTED,
  HR_VERIFIED,
  APPROVED,
  REJECTED,
  /** Offboarding completed (§3.6 stage 3) — terminal; login disabled, record retained. */
  OFFBOARDED
}
