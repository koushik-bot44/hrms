package com.ihrms.domain.enums;

/**
 * Lifecycle of one sent offboarding document (§3.6 stage 2). PG enum type {@code "OffboardingDocStatus"}.
 * Mirrors the Form-4 verification loop: the employee submits, HR verifies or sends back for revision.
 */
public enum OffboardingDocStatus {
  /** Sent to the employee; awaiting their fill + signature. */
  PENDING,
  /** The employee submitted; awaiting HR verification. */
  SUBMITTED,
  /** HR verified the submission. */
  VERIFIED,
  /** HR sent it back with a note; the employee must re-fill and resubmit. */
  REVISION_REQUESTED
}
