package com.ihrms.accountant.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Read-only ATTENDANCE ANALYTICS for the viewer roles (§8a/§2), computed LIVE per request. All grouping
 * is by the persisted shift-day (the overnight 19:00–04:00 attribution) in Asia/Kolkata; worked time is
 * the shared {@code sessions − breaks} computation. Scoped ACCOUNTS_ADMIN (any team) / ACCOUNTANT (own).
 */
public final class ViewerAttendanceDtos {

  private ViewerAttendanceDtos() {}

  @Schema(description = "Approved leave DAYS in the month, split by type (calendar days, clipped to the month).")
  public record LeavesByType(long casual, long sick, long unpaid) {}

  @Schema(
      description =
          "The same-unit split for a donut. worked + break = gross clocked time; idle is not computed in v1.")
  public record TimeComposition(
      long workedSeconds,
      long breakSeconds,
      @Schema(description = "Optional idle seconds; null in v1 (worked + break are the real, summable split).")
          Long idleSeconds) {}

  @Schema(description = "One employee's attendance metrics for a shift-month OR custom range (computed live).")
  public record EmployeeMonthSummary(
      @Schema(
              description =
                  "The shift-month, YYYY-MM (Asia/Kolkata); null for a custom from/to range — use"
                      + " periodStart/periodEnd for the authoritative window.")
          String month,
      @Schema(description = "First day of the reported window, YYYY-MM-DD (IST).") String periodStart,
      @Schema(description = "Last day of the reported window, inclusive, YYYY-MM-DD (IST).") String periodEnd,
      @Schema(description = "Worked seconds = completed sessions' duration MINUS their breaks (open = 0).")
          long workedSeconds,
      @Schema(description = "Break seconds = completed breaks in the month.") long breakSeconds,
      @Schema(description = "Distinct shift-days with at least one session.") long daysPresent,
      @Schema(description = "Sessions flagged is_late (persisted; not recomputed).") long lateLogins,
      LeavesByType leavesByType,
      @Schema(description = "Total approved leave days in the month (sum of leavesByType).")
          long leaveDaysTotal,
      @Schema(description = "Number of approved leave REQUESTS overlapping the month.") long leaveRequests,
      @Schema(description = "Working days in the month per the stated rule (Mon–Fri).") int workingDays,
      @Schema(description = "Adherence denominator = working days − approved leave on working days.")
          long expectedDays,
      @Schema(
              description =
                  "Past working days (before today, IST) with no session and no approved leave. Today/future"
                      + " never count.")
          long unapprovedAbsences,
      @Schema(
              description =
                  "present-on-working ÷ expectedDays as a whole percent; null when expectedDays = 0 (N/A).")
          Integer adherencePct,
      @Schema(description = "Human label of how workingDays / adherence / absence are defined.")
          String workingDaysDefinition,
      TimeComposition timeComposition,
      @Schema(description = "Whether the employee has an OPEN session right now (live).") boolean clockedInNow) {}

  @Schema(description = "A per-month series of the employee's metrics (oldest → newest).")
  public record EmployeeMonthlySeries(String employeeId, List<EmployeeMonthSummary> months) {}

  @Schema(description = "One row of a team's attendance roster for the month.")
  public record TeamAttendanceMemberRow(
      String employeeId,
      String employeeCode,
      String fullName,
      boolean clockedInNow,
      @Schema(description = "Worked seconds this month (breaks excluded).") long workedSeconds,
      @Schema(description = "Late logins this month.") long lateLogins,
      @Schema(description = "Approved leave days this month.") long leaveDaysTotal,
      @Schema(description = "Past working days with no session and no approved leave, this month.")
          long unapprovedAbsences) {}

  @Schema(description = "A team's attendance roll-up for a month OR custom range + a live 'today' snapshot.")
  public record TeamAttendanceSummary(
      String teamId,
      String teamName,
      String companyId,
      @Schema(
              description =
                  "The shift-month, YYYY-MM; null for a custom from/to range — use periodStart/periodEnd.")
          String month,
      @Schema(description = "First day of the reported window, YYYY-MM-DD (IST).") String periodStart,
      @Schema(description = "Last day of the reported window, inclusive, YYYY-MM-DD (IST).") String periodEnd,
      @Schema(description = "Employees with at least one session on today's shift-day.") long presentToday,
      @Schema(description = "Employees with an OPEN session right now.") long clockedInNow,
      @Schema(description = "Employees on an approved leave that covers today (IST calendar date).")
          long onLeaveToday,
      @Schema(description = "Sum of the team's late logins this month.") long totalLateThisMonth,
      int employeeCount,
      List<TeamAttendanceMemberRow> employees) {}
}
