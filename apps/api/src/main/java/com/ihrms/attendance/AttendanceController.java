package com.ihrms.attendance;

import com.ihrms.attendance.dto.AttendanceDtos.ClockStatusView;
import com.ihrms.attendance.dto.AttendanceDtos.MyAttendancePage;
import com.ihrms.attendance.dto.AttendanceDtos.TeamActivityPage;
import com.ihrms.attendance.dto.AttendanceDtos.TeamAttendanceRow;
import com.ihrms.auth.IhrmsPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Attendance (§8a). Employee endpoints ({@code /me}, {@code /clock-*}) act as the credentialed employee
 * (the SecurityConfig rule keeps them {@code authenticated}; the service refuses a non-employee / an
 * employee without a mailbox with 403). The {@code /team/**} endpoints are MANAGER-only (URL rule) and
 * scoped to his team. The send-graph-style 403/409s are thrown from the service, not via @PreAuthorize.
 */
@RestController
@RequestMapping("/attendance")
public class AttendanceController {

  private final AttendanceService attendance;

  public AttendanceController(AttendanceService attendance) {
    this.attendance = attendance;
  }

  // --- Employee ---------------------------------------------------------------

  /** Clock in — 409 if already clocked in. */
  @PostMapping("/clock-in")
  public ClockStatusView clockIn(
      @AuthenticationPrincipal IhrmsPrincipal actor, HttpServletRequest request) {
    return attendance.clockIn(actor, request.getRemoteAddr());
  }

  /** Clock out — 409 if not clocked in. */
  @PostMapping("/clock-out")
  public ClockStatusView clockOut(
      @AuthenticationPrincipal IhrmsPrincipal actor, HttpServletRequest request) {
    return attendance.clockOut(actor, request.getRemoteAddr());
  }

  /** Button state + today/week totals (drives the toggle + live display). */
  @GetMapping("/me/status")
  public ClockStatusView status(@AuthenticationPrincipal IhrmsPrincipal actor) {
    return attendance.status(actor);
  }

  /** The caller's own history, day-grouped (Asia/Kolkata), paginated; total for the range. */
  @GetMapping("/me")
  public MyAttendancePage me(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 50, sort = "clockInAt", direction = Sort.Direction.DESC) Pageable pageable) {
    return attendance.myHistory(actor, from, to, pageable);
  }

  // --- Manager (team-scope) ---------------------------------------------------

  /** Roster: each team-scope employee with clocked-in state + today/period hours. */
  @GetMapping("/team/summary")
  public List<TeamAttendanceRow> teamSummary(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @AuthenticationPrincipal IhrmsPrincipal.User manager) {
    return attendance.teamSummary(manager, from, to);
  }

  /** Drill-down: ONE team-scope employee's day-grouped history. Cross-scope employeeId -> 403. */
  @GetMapping("/team")
  public MyAttendancePage teamEmployee(
      @RequestParam String employeeId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @AuthenticationPrincipal IhrmsPrincipal.User manager,
      @PageableDefault(size = 50, sort = "clockInAt", direction = Sort.Direction.DESC) Pageable pageable) {
    return attendance.teamEmployeeHistory(manager, employeeId, from, to, pageable);
  }

  /** The pull-based attendance activity feed for his team (newest first). No notification-bell entries. */
  @GetMapping("/team/activity")
  public TeamActivityPage teamActivity(
      @AuthenticationPrincipal IhrmsPrincipal.User manager,
      @PageableDefault(size = 30) Pageable pageable) {
    return attendance.teamActivity(manager, pageable);
  }
}
