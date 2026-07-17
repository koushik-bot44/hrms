package com.ihrms.accountant;

import com.ihrms.accountant.dto.ViewerAttendanceDtos.EmployeeMonthSummary;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.EmployeeMonthlySeries;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.LeavesByType;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TeamAttendanceMemberRow;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TeamAttendanceSummary;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TimeComposition;
import com.ihrms.attendance.AttendanceMath;
import com.ihrms.attendance.ShiftConfig;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.enums.LeaveType;
import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.LeaveRequest;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.AttendanceBreakRepository;
import com.ihrms.domain.repository.AttendanceSessionRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.LeaveRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only attendance ANALYTICS for the viewer roles (§8a/§2), computed LIVE on every call. Authorization
 * + team membership are delegated to {@link AccountantService} (Prompt-1 scoping): ACCOUNTS_ADMIN reads
 * any team/employee, an ACCOUNTANT only their own team (foreign -> 404). Worked/break time uses the shared
 * {@link AttendanceMath} computation; months are grouped by the persisted shift-day (Asia/Kolkata).
 */
@Service
public class ViewerAttendanceService {

  /** v1 working-days rule — labeled so the UI can show exactly what adherence means; swap-ready. */
  private static final String WORKING_DAYS_DEFINITION =
      "Calendar days in the shift-month (all days count; adherence = days present ÷ calendar days).";

  private final AccountantService accountant;
  private final AttendanceSessionRepository sessions;
  private final AttendanceBreakRepository breaks;
  private final LeaveRequestRepository leaves;
  private final EmployeeRepository employees;

  public ViewerAttendanceService(
      AccountantService accountant,
      AttendanceSessionRepository sessions,
      AttendanceBreakRepository breaks,
      LeaveRequestRepository leaves,
      EmployeeRepository employees) {
    this.accountant = accountant;
    this.sessions = sessions;
    this.breaks = breaks;
    this.leaves = leaves;
    this.employees = employees;
  }

  // --- One employee ---------------------------------------------------------

  /** One employee's metrics for a shift-month (default = current). Authorized as a viewer read (§2). */
  @Transactional(readOnly = true)
  public EmployeeMonthSummary employeeSummary(
      IhrmsPrincipal.User actor, String employeeId, String monthParam) {
    Employee employee = accountant.assertViewableEmployee(actor, employeeId);
    YearMonth ym = parseMonth(monthParam);
    List<AttendanceSession> monthSessions =
        sessions.findByEmployeeIdAndShiftDateBetween(employee.getId(), ym.atDay(1), ym.atEndOfMonth());
    List<LeaveRequest> approved =
        leaves.findByEmployeeIdAndStatus(employee.getId(), LeaveStatus.APPROVED);
    return computeMonth(ym, monthSessions, breaksBySession(monthSessions), approved, clockedInNow(employee.getId()));
  }

  /** A per-month series (last {@code months}, default 6) ending with the current shift-month. */
  @Transactional(readOnly = true)
  public EmployeeMonthlySeries employeeMonthly(
      IhrmsPrincipal.User actor, String employeeId, int months) {
    Employee employee = accountant.assertViewableEmployee(actor, employeeId);
    int n = Math.max(1, Math.min(months, 24));
    YearMonth current = YearMonth.now(ShiftConfig.ZONE);
    YearMonth start = current.minusMonths(n - 1L);
    // Load the whole span once, then compute each month in memory (no per-month round-trips).
    List<AttendanceSession> span =
        sessions.findByEmployeeIdAndShiftDateBetween(
            employee.getId(), start.atDay(1), current.atEndOfMonth());
    Map<String, List<AttendanceBreak>> breaksById = breaksBySession(span);
    List<LeaveRequest> approved =
        leaves.findByEmployeeIdAndStatus(employee.getId(), LeaveStatus.APPROVED);
    boolean clockedInNow = clockedInNow(employee.getId());

    List<EmployeeMonthSummary> series =
        java.util.stream.IntStream.range(0, n)
            .mapToObj(
                i -> {
                  YearMonth ym = start.plusMonths(i);
                  List<AttendanceSession> monthSessions =
                      span.stream().filter(s -> YearMonth.from(s.getShiftDate()).equals(ym)).toList();
                  return computeMonth(ym, monthSessions, breaksById, approved, clockedInNow);
                })
            .toList();
    return new EmployeeMonthlySeries(employee.getId(), series);
  }

  // --- Team roll-up ---------------------------------------------------------

  /** A team's month roll-up + a live "today" snapshot. Batched (no N+1). Own-team-only for the Accountant. */
  @Transactional(readOnly = true)
  public TeamAttendanceSummary teamSummary(
      IhrmsPrincipal.User actor, String teamId, String monthParam) {
    Team team = accountant.assertViewableTeam(actor, teamId);
    YearMonth ym = parseMonth(monthParam);
    LocalDate monthStart = ym.atDay(1);
    LocalDate monthEnd = ym.atEndOfMonth();

    List<Employee> members =
        team.getHrUserId() == null
            ? List.of()
            : employees
                .findByCompanyIdAndOnboardingHrId(team.getCompanyId(), team.getHrUserId())
                .stream()
                .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
                .toList();
    List<String> ids = members.stream().map(Employee::getId).toList();
    if (ids.isEmpty()) {
      return new TeamAttendanceSummary(
          team.getId(), team.getName(), team.getCompanyId(), ym.toString(), 0, 0, 0, 0, 0, List.of());
    }

    // 4 batched reads for the whole team.
    List<AttendanceSession> monthSessions =
        sessions.findByCompanyIdAndEmployeeIdInAndShiftDateBetween(
            team.getCompanyId(), ids, monthStart, monthEnd);
    Map<String, List<AttendanceBreak>> breaksById = breaksBySession(monthSessions);
    Map<String, List<AttendanceSession>> sessionsByEmp =
        monthSessions.stream().collect(Collectors.groupingBy(AttendanceSession::getEmployeeId));
    Set<String> openNow =
        sessions.findByEmployeeIdInAndClockOutAtIsNull(ids).stream()
            .map(AttendanceSession::getEmployeeId)
            .collect(Collectors.toSet());
    Map<String, List<LeaveRequest>> leavesByEmp =
        leaves.findByEmployeeIdInAndStatus(ids, LeaveStatus.APPROVED).stream()
            .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));

    LocalDate todayShift = ShiftConfig.shiftDateOf(Instant.now());
    LocalDate todayCal = LocalDate.now(ShiftConfig.ZONE);

    List<TeamAttendanceMemberRow> rows =
        members.stream()
            .map(
                e -> {
                  List<AttendanceSession> ss = sessionsByEmp.getOrDefault(e.getId(), List.of());
                  long worked =
                      ss.stream()
                          .filter(s -> !s.isOpen())
                          .mapToLong(s -> AttendanceMath.workedSeconds(s, breaksById))
                          .sum();
                  long late = ss.stream().filter(AttendanceSession::isLate).count();
                  long leaveDays =
                      leavesByEmp.getOrDefault(e.getId(), List.of()).stream()
                          .mapToLong(lr -> leaveDaysInMonth(lr, monthStart, monthEnd))
                          .sum();
                  return new TeamAttendanceMemberRow(
                      e.getId(),
                      e.getEmployeeCode(),
                      e.getFullName(),
                      openNow.contains(e.getId()),
                      worked,
                      late,
                      leaveDays);
                })
            .sorted(Comparator.comparing(r -> r.fullName() == null ? "" : r.fullName().toLowerCase()))
            .toList();

    long presentToday =
        members.stream()
            .filter(
                e ->
                    sessionsByEmp.getOrDefault(e.getId(), List.of()).stream()
                        .anyMatch(s -> todayShift.equals(s.getShiftDate())))
            .count();
    long onLeaveToday =
        members.stream()
            .filter(
                e ->
                    leavesByEmp.getOrDefault(e.getId(), List.of()).stream()
                        .anyMatch(lr -> covers(lr, todayCal)))
            .count();
    long totalLate = rows.stream().mapToLong(TeamAttendanceMemberRow::lateLogins).sum();

    return new TeamAttendanceSummary(
        team.getId(),
        team.getName(),
        team.getCompanyId(),
        ym.toString(),
        presentToday,
        openNow.size(),
        onLeaveToday,
        totalLate,
        members.size(),
        rows);
  }

  // --- Core computation (ONE place) -----------------------------------------

  private EmployeeMonthSummary computeMonth(
      YearMonth ym,
      List<AttendanceSession> monthSessions,
      Map<String, List<AttendanceBreak>> breaksById,
      List<LeaveRequest> approvedLeaves,
      boolean clockedInNow) {
    LocalDate monthStart = ym.atDay(1);
    LocalDate monthEnd = ym.atEndOfMonth();

    long worked =
        monthSessions.stream()
            .filter(s -> !s.isOpen())
            .mapToLong(s -> AttendanceMath.workedSeconds(s, breaksById))
            .sum();
    long breakSeconds =
        monthSessions.stream()
            .filter(s -> !s.isOpen())
            .mapToLong(s -> AttendanceMath.breakSeconds(s, breaksById))
            .sum();
    long daysPresent =
        monthSessions.stream().map(AttendanceSession::getShiftDate).distinct().count();
    long lateLogins = monthSessions.stream().filter(AttendanceSession::isLate).count();

    long casual = 0;
    long sick = 0;
    long unpaid = 0;
    long leaveDaysTotal = 0;
    long leaveRequests = 0;
    for (LeaveRequest lr : approvedLeaves) {
      long days = leaveDaysInMonth(lr, monthStart, monthEnd);
      if (days <= 0) {
        continue;
      }
      leaveRequests++;
      leaveDaysTotal += days;
      if (lr.getLeaveType() == LeaveType.CASUAL) {
        casual += days;
      } else if (lr.getLeaveType() == LeaveType.SICK) {
        sick += days;
      } else if (lr.getLeaveType() == LeaveType.UNPAID) {
        unpaid += days;
      }
    }

    int workingDays = ym.lengthOfMonth();
    int adherencePct =
        workingDays == 0 ? 0 : (int) Math.round((daysPresent * 100.0) / workingDays);

    return new EmployeeMonthSummary(
        ym.toString(),
        worked,
        breakSeconds,
        daysPresent,
        lateLogins,
        new LeavesByType(casual, sick, unpaid),
        leaveDaysTotal,
        leaveRequests,
        workingDays,
        adherencePct,
        WORKING_DAYS_DEFINITION,
        new TimeComposition(worked, breakSeconds, null), // idle omitted in v1 (worked + break = gross)
        clockedInNow);
  }

  /** Approved leave days that fall inside the month: range clipped to [start,end], inclusive calendar days (v1). */
  private static long leaveDaysInMonth(LeaveRequest lr, LocalDate monthStart, LocalDate monthEnd) {
    LocalDate start = lr.getStartDate().isBefore(monthStart) ? monthStart : lr.getStartDate();
    LocalDate end = lr.getEndDate().isAfter(monthEnd) ? monthEnd : lr.getEndDate();
    if (end.isBefore(start)) {
      return 0;
    }
    return ChronoUnit.DAYS.between(start, end) + 1;
  }

  private static boolean covers(LeaveRequest lr, LocalDate day) {
    return !day.isBefore(lr.getStartDate()) && !day.isAfter(lr.getEndDate());
  }

  private boolean clockedInNow(String employeeId) {
    return sessions.findByEmployeeIdAndClockOutAtIsNull(employeeId).isPresent();
  }

  private Map<String, List<AttendanceBreak>> breaksBySession(List<AttendanceSession> ss) {
    if (ss.isEmpty()) {
      return Map.of();
    }
    List<String> sessionIds = ss.stream().map(AttendanceSession::getId).toList();
    return breaks.findBySessionIdInOrderByBreakStartAtAsc(sessionIds).stream()
        .collect(Collectors.groupingBy(AttendanceBreak::getSessionId));
  }

  /** {@code YYYY-MM} in Asia/Kolkata; blank = current shift-month. Invalid -> 400. */
  private static YearMonth parseMonth(String month) {
    if (month == null || month.isBlank()) {
      return YearMonth.now(ShiftConfig.ZONE);
    }
    try {
      return YearMonth.parse(month.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month must be YYYY-MM");
    }
  }
}
