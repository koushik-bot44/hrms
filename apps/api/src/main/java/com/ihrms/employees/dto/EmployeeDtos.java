package com.ihrms.employees.dto;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OnboardingType;
import com.ihrms.onboarding.dto.OfferDtos.OfferTermsRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2Request;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Employee-onboarding request/response DTOs — JSON shapes match api-contract.md §3.5. */
public final class EmployeeDtos {

  private EmployeeDtos() {}

  /**
   * HR onboards by FILLING FORM 2 — Employee Info (§3.2) — and providing the OFFER terms (§3.2). Submitting
   * creates the record + the SENT offer and sends the invite in one action; the personal email is the login
   * identity. No employee ID is minted here — it is allocated on Manager approval (§5).
   */
  public record OnboardEmployeeRequest(
      @NotNull @Valid Form2Request form2, @NotNull @Valid OfferTermsRequest offer) {}

  /**
   * SUPER_ADMIN onboards into a chosen company (companyId is the path) by selecting a {@code teamId} and
   * filling Form 2 + the offer; the employee attaches to that team's HR (§2). Same payload as the HR form.
   */
  public record SuperAdminOnboardRequest(
      @NotBlank(message = "Team is required") String teamId,
      @NotNull @Valid Form2Request form2,
      @NotNull @Valid OfferTermsRequest offer) {}

  /**
   * HR onboards an EXISTING employee (§3.2): someone already on the payroll with no IHRMS record. Form 2 only
   * — incl. the employee ID + official email they ALREADY have — no offer terms, and NO email is ever sent:
   * HR enters the rest of the record via {@code /employees/{id}/onboarding} and approves, keeping the ID.
   */
  public record OnboardExistingEmployeeRequest(@NotNull @Valid Form2Request form2) {}

  /** SUPER_ADMIN variant of the existing-employee onboard: picks the team; that team's HR enters the record. */
  public record SuperAdminOnboardExistingRequest(
      @NotBlank(message = "Team is required") String teamId, @NotNull @Valid Form2Request form2) {}

  /** {@code employeeCode} is null until the employee is approved (§5 — or §3.2's kept ID for an existing employee). */
  public record EmployeeSummaryView(
      String id,
      String employeeCode,
      String fullName,
      String email,
      String designation,
      String dateOfJoining,
      EmployeeStatus status,
      String createdAt,
      OnboardingType onboardingType) {}

  /** {@code { employee, loginUrl }} — the new record plus the login link that was emailed. */
  public record OnboardEmployeeResult(EmployeeSummaryView employee, String loginUrl) {}

  /** A page of the HR's onboarding queue (their onboarded employees, filtered + paginated). */
  public record EmployeePage(
      List<EmployeeSummaryView> content, int page, int size, long totalElements, int totalPages) {}
}
