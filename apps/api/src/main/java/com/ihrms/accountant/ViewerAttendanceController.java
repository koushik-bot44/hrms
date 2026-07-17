package com.ihrms.accountant;

import com.ihrms.accountant.dto.ViewerAttendanceDtos.EmployeeMonthSummary;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.EmployeeMonthlySeries;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TeamAttendanceSummary;
import com.ihrms.auth.IhrmsPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only attendance analytics for the viewer roles (§8a/§2), computed LIVE. Scoped exactly like the
 * Prompt-1 viewer reads: ACCOUNTS_ADMIN any company/team/employee, ACCOUNTANT only their own team (a
 * foreign team/employee -> 404). GET-only — no mutation endpoints.
 */
@RestController
@RequestMapping("/accountant")
@PreAuthorize("hasAnyRole('ACCOUNTS_ADMIN', 'ACCOUNTANT')")
public class ViewerAttendanceController {

  private final ViewerAttendanceService analytics;

  public ViewerAttendanceController(ViewerAttendanceService analytics) {
    this.analytics = analytics;
  }

  @Operation(summary = "One employee's live attendance metrics for a shift-month (default = current).")
  @GetMapping("/employees/{employeeId}/attendance/summary")
  public EmployeeMonthSummary employeeSummary(
      @PathVariable String employeeId,
      @RequestParam(required = false) String month,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return analytics.employeeSummary(actor, employeeId, month);
  }

  @Operation(summary = "An employee's per-month metric series (last N shift-months; default 6).")
  @GetMapping("/employees/{employeeId}/attendance/monthly")
  public EmployeeMonthlySeries employeeMonthly(
      @PathVariable String employeeId,
      @RequestParam(required = false, defaultValue = "6") int months,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return analytics.employeeMonthly(actor, employeeId, months);
  }

  @Operation(summary = "A team's month roll-up + live today snapshot (own-team-only for the Accountant).")
  @GetMapping("/teams/{teamId}/attendance/summary")
  public TeamAttendanceSummary teamSummary(
      @PathVariable String teamId,
      @RequestParam(required = false) String month,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return analytics.teamSummary(actor, teamId, month);
  }
}
