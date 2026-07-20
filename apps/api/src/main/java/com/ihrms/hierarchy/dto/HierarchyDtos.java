package com.ihrms.hierarchy.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ihrms.domain.enums.EmployeeStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Hierarchy DTOs (ARCHITECTURE.md §2/§6). The Hierarchy is the single cross-platform, READ-ONLY,
 * AGGREGATES-ONLY overview role. Provisioning is SUPER_ADMIN-only (singleton), mirroring the Accounts
 * Admin: the mailbox local part forms {@code localPart@ihrms} (the platform domain) and IS the login
 * email. springdoc-visible so the web types regenerate. (No data-read DTOs yet — aggregate reads land
 * in a later stage under {@code /hierarchy/**}.)
 */
public final class HierarchyDtos {

  private HierarchyDtos() {}

  /** SUPER_ADMIN creates the single Hierarchy user with an initial staff password (§6). */
  public record ProvisionHierarchyRequest(
      @NotBlank(message = "Name is required") @Size(min = 2, max = 120, message = "Name is too long")
          String name,
      @NotBlank(message = "A mailbox name is required")
          @Size(max = 64, message = "Mailbox name is too long")
          String localPart,
      @NotBlank(message = "An initial password is required")
          @Size(min = 8, message = "Use at least 8 characters")
          String password) {}

  public record HierarchyView(
      String id, String email, String name, String status, String createdAt) {}

  /** {@code devPassword} omitted (not null) in production. */
  public record ProvisionHierarchyResult(
      HierarchyView hierarchy, @JsonInclude(JsonInclude.Include.NON_NULL) String devPassword) {}

  /** Whether the singleton Hierarchy exists yet — drives the SUPER_ADMIN provisioning UI. */
  public record HierarchyStatus(
      boolean exists, @JsonInclude(JsonInclude.Include.NON_NULL) HierarchyView hierarchy) {}

  // --- Platform overview (§2; cross-company, aggregates-only, no PII) --------

  @Schema(description = "Companies on the platform: total, active and archived (status = DELETED).")
  public record CompanyTotals(long total, long active, long archived) {}

  @Schema(description = "Currently-assigned staff counts by role (a count, never a people list).")
  public record StaffTotals(
      long companyAdmins, long hrs, long managers, long accountants, long accountsAdmins) {}

  @Schema(description = "Platform totals: companies, teams, employees and staff-by-role counts.")
  public record PlatformTotals(CompanyTotals companies, long teams, long employees, StaffTotals staff) {}

  @Schema(description = "The current onboarding funnel — how many employees sit at each status right now.")
  public record OnboardingFunnel(
      long invited,
      long inProgress,
      long submitted,
      long revisionRequested,
      long hrVerified,
      long approved,
      long rejected) {}

  @Schema(description = "Employees stuck at a given pre-approval stage past the threshold.")
  public record StuckStage(EmployeeStatus status, long count) {}

  @Schema(description = "Operational metrics over the whole platform (all-time unless noted).")
  public record OpsMetrics(
      @Schema(description = "Every onboarded employee record (a record exists only once onboarded).")
          long totalOnboarded,
      @Schema(description = "Employees currently APPROVED.") long approved,
      @Schema(description = "approved ÷ totalOnboarded (0 when none onboarded).")
          double onboardingCompletionRate,
      @Schema(
              description =
                  "Mean days between Employee.createdAt and ApprovalRequest.decidedAt over approved"
                      + " employees; null when none approved.")
          Double averageTimeToApprovalDays,
      @Schema(description = "The stuck-onboarding age threshold, in days (configurable constant).")
          int stuckThresholdDays,
      @Schema(description = "Pre-approval employees older than the threshold (by Employee.createdAt).")
          long stuckOnboardings,
      @Schema(description = "The stuck count broken down by the stage they're stuck at.")
          List<StuckStage> stuckByStage) {}

  @Schema(description = "The one-call platform overview: totals + funnel + ops metrics.")
  public record PlatformOverview(
      PlatformTotals totals,
      OnboardingFunnel funnel,
      @Schema(description = "Same counts as the funnel — the UI computes the distribution %.")
          OnboardingFunnel statusDistribution,
      OpsMetrics ops) {}

  // --- Trends (monthly series, Asia/Kolkata) --------------------------------

  @Schema(description = "One month of the trends series (YYYY-MM, IST). offboarded is a 0 placeholder.")
  public record TrendPoint(String month, long joined, long approved, long offboarded) {}

  public record TrendsResponse(int months, List<TrendPoint> series) {}

  // --- Companies (size distribution + drill list) ---------------------------

  @Schema(description = "One company's size row: name, archived flag, team + employee counts.")
  public record CompanySizeRow(
      String id, String name, String code, boolean archived, long teamCount, long employeeCount) {}

  public record CompaniesResponse(List<CompanySizeRow> companies) {}

  // --- Per-company org breakdown (names STAFF — org data — never employee PII) ---

  @Schema(description = "An assigned staff member (name + email); null when the slot is unassigned.")
  public record StaffRef(String name, String email) {}

  @Schema(description = "One team's org row — its assigned HR/Manager/Accountant + employee count.")
  public record TeamBreakdown(
      String teamId,
      String name,
      @JsonInclude(JsonInclude.Include.NON_NULL) StaffRef hr,
      @JsonInclude(JsonInclude.Include.NON_NULL) StaffRef manager,
      @JsonInclude(JsonInclude.Include.NON_NULL) StaffRef accountant,
      long employeeCount) {}

  @Schema(
      description =
          "One company's org structure: counts + by-status + the assigned Company Admin + its teams'"
              + " assigned staff. Names STAFF only — no employee identity/PII.")
  public record CompanyBreakdown(
      String companyId,
      String name,
      boolean archived,
      long teamCount,
      long employeeCount,
      OnboardingFunnel byStatus,
      @JsonInclude(JsonInclude.Include.NON_NULL) StaffRef companyAdmin,
      List<TeamBreakdown> teams) {}
}
