package com.ihrms.domain.enums;

/**
 * Lifecycle of a single sent agreement (§Agreements). PG enum type {@code "AgreementStatus"}. There is no
 * verification loop — the employee's submission is terminal.
 */
public enum AgreementStatus {
  /** Sent to the employee; awaiting their read + fill + signature. */
  PENDING,
  /** The employee has signed and submitted; a rendered PDF is stored. */
  COMPLETED
}
