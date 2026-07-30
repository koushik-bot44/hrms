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
 * Prompt-1 viewer reads: ACCOUNTS_ADMIN any company/team/employee, ACCOUNTANT only their own team, and a
 * MANAGER only their own team (a foreign team/employee -> 404). This controller holds ONLY the attendance
 * analytics endpoints, so the class gate may include MANAGER without widening the rest of {@code
 * /accountant/**} (the SecurityConfig per-path rules mirror this exactly). GET-only — no mutations.
 *
 * <p>Each summary read accepts EITHER {@code month=YYYY-MM} (default = current shift-month) OR a custom
 * {@code from=YYYY-MM-DD&to=YYYY-MM-DD} range (mutually exclusive); the per-month series is inherently
 * monthly and takes neither.
 */
@RestController
@RequestMapping("/accountant")
@PreAuthorize("hasAnyRole('ACCOUNTS_ADMIN', 'ACCOUNTANT', 'MANAGER')")
public class ViewerAttendanceController {

  private final ViewerAttendanceService analytics;

  public ViewerAttendanceController(ViewerAttendanceService analytics) {
    this.analytics = analytics;
  }

  @Operation(
      summary =
          "One employee's live attendance metrics for a shift-month (default = current) or a custom"
              + " from/to range.")
  @GetMapping("/employees/{employeeId}/attendance/summary")
  public EmployeeMonthSummary employeeSummary(
      @PathVariable String employeeId,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return analytics.employeeSummary(actor, employeeId, month, from, to);
  }

  @Operation(summary = "An employee's per-month metric series (last N shift-months; default 6).")
  @GetMapping("/employees/{employeeId}/attendance/monthly")
  public EmployeeMonthlySeries employeeMonthly(
      @PathVariable String employeeId,
      @RequestParam(required = false, defaultValue = "6") int months,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return analytics.employeeMonthly(actor, employeeId, months);
  }

  @Operation(
      summary =
          "A team's roll-up + live today snapshot for a shift-month (default = current) or a custom"
              + " from/to range (own-team-only for the Accountant and the Manager).")
  @GetMapping("/teams/{teamId}/attendance/summary")
  public TeamAttendanceSummary teamSummary(
      @PathVariable String teamId,
      @RequestParam(required = false) String month,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return analytics.teamSummary(actor, teamId, month, from, to);
  }
}
