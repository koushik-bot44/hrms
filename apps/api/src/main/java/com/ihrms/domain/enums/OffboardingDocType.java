package com.ihrms.domain.enums;

/**
 * The employee-facing offboarding documents (§3.6 stage 2). PG enum type {@code "OffboardingDocType"}.
 * The Clearance form is a separate HR-side checklist, not one of these.
 */
public enum OffboardingDocType {
  /** Separation & Exit Formalities notice — read + acknowledge only. */
  EXIT_FORMALITIES,
  /** Settlement Agreement — per-case values + employee fills. */
  SETTLEMENT,
  /** Employee Separation Agreement & Release — per-case values + employee fills. */
  SEPARATION
}
