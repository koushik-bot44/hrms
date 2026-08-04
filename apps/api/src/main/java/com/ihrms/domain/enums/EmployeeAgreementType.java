package com.ihrms.domain.enums;

/**
 * The three standard post-approval company agreements (§Agreements). PG enum type
 * {@code "EmployeeAgreementType"}. Sent as one pack by HR after an employee is APPROVED.
 */
public enum EmployeeAgreementType {
  /** Acceptable Use Policy. */
  AUP,
  /** Non-Disclosure & Non-Compete Agreement. */
  NDA,
  /** Notice Period Conduct Guidelines. */
  NOTICE_PERIOD
}
