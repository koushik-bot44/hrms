package com.ihrms.domain.support;

import java.util.regex.Pattern;

/**
 * Employee-ID helpers (ARCHITECTURE.md §5), mirroring the archived shared/ids.ts.
 * Format: {@code {COMPANY_CODE}-EMP-{NNNNNN}} e.g. {@code ACME-EMP-000123}.
 */
public final class EmployeeCodes {

  public static final Pattern COMPANY_CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,15}$");
  public static final Pattern EMPLOYEE_CODE = Pattern.compile("^[A-Z][A-Z0-9]{1,15}-EMP-\\d{6}$");
  public static final int SEQ_MIN = 1;
  public static final int SEQ_MAX = 999_999;

  private EmployeeCodes() {}

  /** Build an employee code from a company code + sequence. Throws on invalid input. */
  public static String format(String companyCode, int sequence) {
    if (companyCode == null || !COMPANY_CODE.matcher(companyCode).matches()) {
      throw new IllegalArgumentException("Invalid company code: " + companyCode);
    }
    if (sequence < SEQ_MIN || sequence > SEQ_MAX) {
      throw new IllegalArgumentException(
          "Invalid sequence: " + sequence + " (expected " + SEQ_MIN + ".." + SEQ_MAX + ")");
    }
    return companyCode + "-EMP-" + String.format("%06d", sequence);
  }

  public static boolean isValid(String value) {
    return value != null && EMPLOYEE_CODE.matcher(value).matches();
  }
}
