package com.ihrms.employees.dto;

import com.ihrms.domain.enums.EmployeeStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Employee-onboarding request/response DTOs — JSON shapes match api-contract.md §3.5 exactly. */
public final class EmployeeDtos {

  private EmployeeDtos() {}

  public record OnboardEmployeeRequest(
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email) {}

  public record EmployeeSummaryView(
      String id, String employeeCode, String email, EmployeeStatus status, String createdAt) {}

  /** {@code { employee, loginUrl }} — the new record plus the login link that was emailed. */
  public record OnboardEmployeeResult(EmployeeSummaryView employee, String loginUrl) {}
}
