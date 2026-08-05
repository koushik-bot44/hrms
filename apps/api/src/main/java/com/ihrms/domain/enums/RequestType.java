package com.ihrms.domain.enums;

/**
 * Document-request kinds (§8d / §3.6 stage 3). PG enum type {@code "RequestType"}. Most route to the team
 * Accountant; the two offboarding LETTER types route to the offboarding case's HR instead.
 */
public enum RequestType {
  PAYSLIP,
  SALARY_CERTIFICATE,
  FORM16,
  TAX_DOCUMENT,
  OTHER,
  /** §3.6 stage 3 — routed to the offboarding case HR, gated on all sent documents being verified. */
  RELIEVING_LETTER,
  EXPERIENCE_LETTER
}
