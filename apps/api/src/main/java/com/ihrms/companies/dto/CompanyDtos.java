package com.ihrms.companies.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Company-management request/response DTOs — JSON shapes match api-contract.md §3.3 exactly. */
public final class CompanyDtos {

  private CompanyDtos() {}

  public record CreateCompanyRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name,
      // Upper-cased + COMPANY_CODE_REGEX-validated in the service.
      @NotBlank(message = "Code is required") String code,
      // Internal-mail domain (§8); optional — defaults to the lowercased code when omitted.
      @Size(max = 63, message = "Mail domain is too long") String mailDomain) {}

  /** At least one field required (enforced in the service). */
  public record UpdateCompanyRequest(
      @Size(min = 2, max = 120, message = "Name is too long") String name,
      @Pattern(regexp = "ACTIVE|SUSPENDED", message = "status must be ACTIVE or SUSPENDED")
          String status) {}

  /**
   * The mailbox local part (§8) forms the Company Admin's address {@code localPart@companyDomain}, which
   * IS their login email — the single way an address is created. Validated + normalized by
   * {@code MailAddresses}.
   */
  public record ProvisionAdminRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name,
      @NotBlank(message = "A mailbox name is required")
          @Size(max = 64, message = "Mailbox name is too long")
          String localPart,
      // The Company Admin's initial sign-in password (staff use email + password, §6).
      @NotBlank(message = "An initial password is required")
          @Size(min = 8, message = "Use at least 8 characters")
          String password) {}

  public record CompanyAdminView(
      String id, String email, String name, String status, String createdAt) {}

  public record CompanySummaryView(
      String id,
      String name,
      String code,
      String status,
      long teamCount,
      long employeeCount,
      boolean hasAdmin,
      String createdAt,
      String deletedAt) {}

  /** {@code CompanyDetail = CompanySummary & { mailDomain, admin }}. */
  public record CompanyDetailView(
      String id,
      String name,
      String code,
      // The company's internal-mail domain (§8) — drives the address preview when provisioning staff.
      String mailDomain,
      String status,
      long teamCount,
      long employeeCount,
      boolean hasAdmin,
      String createdAt,
      String deletedAt,
      CompanyAdminView admin) {}

  /** {@code devPassword} omitted (not null) in production. */
  public record ProvisionAdminResult(
      CompanyAdminView admin, @JsonInclude(JsonInclude.Include.NON_NULL) String devPassword) {}

  /** Result of a permanent purge — the company is gone; echoes what was removed. */
  public record PurgeCompanyResult(String id, String name, String code) {}
}
