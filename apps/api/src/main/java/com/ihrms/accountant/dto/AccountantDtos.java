package com.ihrms.accountant.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Accountant DTOs (ARCHITECTURE.md §2/§6). The Accountant is the single cross-company, READ-ONLY
 * viewer of APPROVED employees. Provisioning is SUPER_ADMIN-only (singleton); the read views reuse the
 * shared {@code EmployeeRecordView}/{@code RevealedSensitive} shapes so the web record view is identical
 * to HR's (masked by default, audited reveal). springdoc-visible so the web types regenerate.
 */
public final class AccountantDtos {

  private AccountantDtos() {}

  /**
   * SUPER_ADMIN creates the single Accounts Admin with an initial staff password (§6). The mailbox
   * local part forms {@code localPart@ihrms} (the platform domain) and IS the login email (§8);
   * {@code email} is a transitional fallback.
   */
  public record ProvisionAccountantRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name,
      @Size(max = 64, message = "Mailbox name is too long") String localPart,
      @Email(message = "Enter a valid email") String email,
      @NotBlank(message = "An initial password is required")
          @Size(min = 8, message = "Use at least 8 characters")
          String password) {}

  public record AccountantView(
      String id, String email, String name, String status, String createdAt) {}

  /** {@code devPassword} omitted (not null) in production. */
  public record ProvisionAccountantResult(
      AccountantView accountant, @JsonInclude(JsonInclude.Include.NON_NULL) String devPassword) {}

  /** Whether the singleton Accountant exists yet — drives the SUPER_ADMIN provisioning UI. */
  public record AccountantStatus(
      boolean exists, @JsonInclude(JsonInclude.Include.NON_NULL) AccountantView accountant) {}

  /** One row of the cross-company approved-employees list (DataTable-friendly). */
  public record ApprovedEmployeeRow(
      String id,
      String employeeCode,
      String fullName,
      String email,
      String companyId,
      String companyName,
      String designation,
      String dateOfJoining,
      String approvedAt) {}

  public record ApprovedEmployeePage(
      List<ApprovedEmployeeRow> content,
      int page,
      int size,
      long totalElements,
      int totalPages) {}
}
