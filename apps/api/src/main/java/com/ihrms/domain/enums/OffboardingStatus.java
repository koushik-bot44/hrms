package com.ihrms.domain.enums;

/**
 * Offboarding case lifecycle (§Offboarding, stage 1). PG enum type {@code "OffboardingStatus"}.
 * {@code COMPLETED} arrives in stage 3. PENDING_APPROVAL and APPROVED are the non-terminal states (at most
 * one per employee).
 */
public enum OffboardingStatus {
  /** HR initiated; awaiting the HIERARCHY approval decision. */
  PENDING_APPROVAL,
  /** Approved by the HIERARCHY role; the process may proceed (documents/completion in later stages). */
  APPROVED,
  /** Rejected by the HIERARCHY role — terminal for this case (HR may initiate a fresh one). */
  REJECTED,
  /** Cancelled by HR before completion — terminal. */
  CANCELLED
}
