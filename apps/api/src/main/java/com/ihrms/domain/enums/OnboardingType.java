package com.ihrms.domain.enums;

/**
 * How the record was opened (§3.2). PG enum type {@code "OnboardingType"}. A {@code NEW_HIRE} gets the offer
 * letter + selection email and onboards themselves; an {@code EXISTING_EMPLOYEE} (already on the payroll, no
 * IHRMS record yet) gets no offer and no email — HR enters their record and approves it, keeping the employee
 * ID they already have.
 */
public enum OnboardingType {
  NEW_HIRE,
  EXISTING_EMPLOYEE
}
