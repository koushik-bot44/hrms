package com.ihrms.employees.dto;

import com.ihrms.domain.enums.EmployeeStatus;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Employee-onboarding request/response DTOs — JSON shapes match api-contract.md §3.5. */
public final class EmployeeDtos {

  private EmployeeDtos() {}

  /**
   * HR onboards with the employee's full name, email, designation (job title) and date of joining.
   * No employee ID is minted here — it is allocated on Manager approval (§5).
   */
  public record OnboardEmployeeRequest(
      @NotBlank(message = "Full name is required") @Size(max = 120, message = "Full name is too long")
          String fullName,
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank(message = "Designation is required")
          @Size(max = 120, message = "Designation is too long")
          String designation,
      @NotNull(message = "Date of joining is required") LocalDate dateOfJoining) {}

  /**
   * SUPER_ADMIN onboards into a chosen company (companyId is the path) by selecting a {@code teamId};
   * the employee attaches to that team's HR (§2). Same employee fields as the HR form.
   */
  public record SuperAdminOnboardRequest(
      @NotBlank(message = "Team is required") String teamId,
      @NotBlank(message = "Full name is required") @Size(max = 120, message = "Full name is too long")
          String fullName,
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank(message = "Designation is required")
          @Size(max = 120, message = "Designation is too long")
          String designation,
      @NotNull(message = "Date of joining is required") LocalDate dateOfJoining) {}

  /** {@code employeeCode} is null until the employee is approved (§5). */
  public record EmployeeSummaryView(
      String id,
      String employeeCode,
      String fullName,
      String email,
      String designation,
      String dateOfJoining,
      EmployeeStatus status,
      String createdAt) {}

  /** {@code { employee, loginUrl }} — the new record plus the login link that was emailed. */
  public record OnboardEmployeeResult(EmployeeSummaryView employee, String loginUrl) {}

  /** A page of the HR's onboarding queue (their onboarded employees, filtered + paginated). */
  public record EmployeePage(
      List<EmployeeSummaryView> content, int page, int size, long totalElements, int totalPages) {}
}
