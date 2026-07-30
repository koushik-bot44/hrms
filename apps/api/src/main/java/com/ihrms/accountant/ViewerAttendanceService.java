package com.ihrms.accountant;

import com.ihrms.accountant.dto.ViewerAttendanceDtos.EmployeeMonthSummary;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.EmployeeMonthlySeries;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.LeavesByType;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TeamAttendanceMemberRow;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TeamAttendanceSummary;
import com.ihrms.accountant.dto.ViewerAttendanceDtos.TimeComposition;
import com.ihrms.attendance.AttendanceCalendar;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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

  /**
   * One employee's metrics for a shift-month (default = current) OR a custom from/to range. Authorized as a
   * viewer read (§2/§8a): ACCOUNTS_ADMIN any, ACCOUNTANT/MANAGER only their own team's employee (else 404).
   */
  @Transactional(readOnly = true)
  public EmployeeMonthSummary employeeSummary(
      IhrmsPrincipal.User actor, String employeeId, String monthParam, String fromParam, String toParam) {
    Employee employee = accountant.assertViewableEmployee(actor, employeeId);
    Period period = resolvePeriod(monthParam, fromParam, toParam);
    List<AttendanceSession> periodSessions =
        sessions.findByEmployeeIdAndShiftDateBetween(employee.getId(), period.start(), period.end());
    List<LeaveRequest> approved =
        leaves.findByEmployeeIdAndStatus(employee.getId(), LeaveStatus.APPROVED);
    return computePeriod(
        period,
        periodSessions,
        breaksBySession(periodSessions),
        approved,
        LocalDate.now(ShiftConfig.ZONE),
        clockedInNow(employee.getId()));
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
    LocalDate today = LocalDate.now(ShiftConfig.ZONE);

    List<EmployeeMonthSummary> series =
        java.util.stream.IntStream.range(0, n)
            .mapToObj(
                i -> {
                  YearMonth ym = start.plusMonths(i);
                  List<AttendanceSession> monthSessions =
                      span.stream().filter(s -> YearMonth.from(s.getShiftDate()).equals(ym)).toList();
                  return computeMonth(ym, monthSessions, breaksById, approved, today, clockedInNow);
                })
            .toList();
    return new EmployeeMonthlySeries(employee.getId(), series);
  }

  // --- Team roll-up ---------------------------------------------------------

  /**
   * A team's roll-up for a shift-month (default = current) OR a custom from/to range + a live "today"
   * snapshot. Batched (no N+1). Own-team-only for the Accountant AND the Manager (§8a). The today-snapshot
   * tiles (present today / clocked-in now / on leave today) are inherently NOW and never range.
   */
  @Transactional(readOnly = true)
  public TeamAttendanceSummary teamSummary(
      IhrmsPrincipal.User actor, String teamId, String monthParam, String fromParam, String toParam) {
    Team team = accountant.assertViewableTeam(actor, teamId);
    Period period = resolvePeriod(monthParam, fromParam, toParam);
    LocalDate periodStart = period.start();
    LocalDate periodEnd = period.end();

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
          team.getId(),
          team.getName(),
          team.getCompanyId(),
          period.monthLabel(),
          periodStart.toString(),
          periodEnd.toString(),
          0,
          0,
          0,
          0,
          0,
          List.of(),
          new TimeComposition(0, 0, null),
          0,
          new LeavesByType(0, 0, 0),
          0,
          0,
          0,
          null);
    }

    // 4 batched reads for the whole team.
    List<AttendanceSession> periodSessions =
        sessions.findByCompanyIdAndEmployeeIdInAndShiftDateBetween(
            team.getCompanyId(), ids, periodStart, periodEnd);
    Map<String, List<AttendanceBreak>> breaksById = breaksBySession(periodSessions);
    Map<String, List<AttendanceSession>> sessionsByEmp =
        periodSessions.stream().collect(Collectors.groupingBy(AttendanceSession::getEmployeeId));
    Set<String> openNow =
        sessions.findByEmployeeIdInAndClockOutAtIsNull(ids).stream()
            .map(AttendanceSession::getEmployeeId)
            .collect(Collectors.toSet());
    Map<String, List<LeaveRequest>> leavesByEmp =
        leaves.findByEmployeeIdInAndStatus(ids, LeaveStatus.APPROVED).stream()
            .collect(Collectors.groupingBy(LeaveRequest::getEmployeeId));

    // Mon–Fri working days of the reported window — computed once, reused for every member's absences.
    List<LocalDate> workingDays = AttendanceCalendar.workingDays(periodStart, periodEnd);
    // Snapshot pivots are always NOW (never the selected window): today's shift-day + IST calendar date.
    LocalDate todayShift = ShiftConfig.shiftDateOf(Instant.now());
    LocalDate todayCal = LocalDate.now(ShiftConfig.ZONE);

    // Each member's full metrics come from the ONE aggregation (computePeriod) — the member row AND the
    // team totals are built from the same numbers, so a team total is literally the sum of its members.
    List<TeamAttendanceMemberRow> rows = new ArrayList<>();
    long teamWorked = 0;
    long teamBreak = 0;
    long teamDaysPresent = 0;
    long teamLeaveDays = 0;
    long teamAbsences = 0;
    long teamExpected = 0;
    long teamPresentWorking = 0; // numerator for the team adherence (Σ present-on-working)
    long teamCasual = 0;
    long teamSick = 0;
    long teamUnpaid = 0;
    for (Employee e : members) {
      List<AttendanceSession> ss = sessionsByEmp.getOrDefault(e.getId(), List.of());
      List<LeaveRequest> empLeaves = leavesByEmp.getOrDefault(e.getId(), List.of());
      EmployeeMonthSummary ms =
          computePeriod(period, ss, breaksById, empLeaves, todayCal, openNow.contains(e.getId()));
      rows.add(
          new TeamAttendanceMemberRow(
              e.getId(),
              e.getEmployeeCode(),
              e.getFullName(),
              openNow.contains(e.getId()),
              ms.workedSeconds(),
              ms.lateLogins(),
              ms.leaveDaysTotal(),
              ms.unapprovedAbsences()));
      teamWorked += ms.workedSeconds();
      teamBreak += ms.breakSeconds();
      teamDaysPresent += ms.daysPresent();
      teamLeaveDays += ms.leaveDaysTotal();
      teamAbsences += ms.unapprovedAbsences();
      teamExpected += ms.expectedDays();
      teamCasual += ms.leavesByType().casual();
      teamSick += ms.leavesByType().sick();
      teamUnpaid += ms.leavesByType().unpaid();
      // present-on-working (Mon–Fri days with a session) — the adherence numerator computePeriod uses.
      Set<LocalDate> present =
          ss.stream().map(AttendanceSession::getShiftDate).collect(Collectors.toSet());
      teamPresentWorking += workingDays.stream().filter(present::contains).count();
    }
    rows.sort(Comparator.comparing(r -> r.fullName() == null ? "" : r.fullName().toLowerCase()));

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
    Integer teamAdherence =
        teamExpected <= 0 ? null : (int) Math.round((teamPresentWorking * 100.0) / teamExpected);

    return new TeamAttendanceSummary(
        team.getId(),
        team.getName(),
        team.getCompanyId(),
        period.monthLabel(),
        periodStart.toString(),
        periodEnd.toString(),
        presentToday,
        openNow.size(),
        onLeaveToday,
        totalLate,
        members.size(),
        rows,
        new TimeComposition(teamWorked, teamBreak, null),
        teamDaysPresent,
        new LeavesByType(teamCasual, teamSick, teamUnpaid),
        teamLeaveDays,
        teamAbsences,
        teamExpected,
        teamAdherence);
  }

  // --- Core computation (ONE place) -----------------------------------------

  /** The month path (also the series): a shift-month is just the period [firstDay, lastDay] + its label. */
  private EmployeeMonthSummary computeMonth(
      YearMonth ym,
      List<AttendanceSession> monthSessions,
      Map<String, List<AttendanceBreak>> breaksById,
      List<LeaveRequest> approvedLeaves,
      LocalDate today,
      boolean clockedInNow) {
    return computePeriod(
        new Period(ym.atDay(1), ym.atEndOfMonth(), ym.toString()),
        monthSessions,
        breaksById,
        approvedLeaves,
        today,
        clockedInNow);
  }

  /**
   * The ONE aggregation, over an arbitrary window {@code [period.start, period.end]} inclusive. A month and
   * a custom range share the exact same rules — sessions by shift-date in range, worked/break/present/late
   * over the range, approved-leave clipped to the range (calendar days for the totals; Mon–Fri days for
   * adherence + absence), unapproved absences past-only. The label is the YYYY-MM for a month, null for a
   * custom range; periodStart/periodEnd always carry the authoritative window.
   */
  private EmployeeMonthSummary computePeriod(
      Period period,
      List<AttendanceSession> periodSessions,
      Map<String, List<AttendanceBreak>> breaksById,
      List<LeaveRequest> approvedLeaves,
      LocalDate today,
      boolean clockedInNow) {
    LocalDate start = period.start();
    LocalDate end = period.end();

    long worked =
        periodSessions.stream()
            .filter(s -> !s.isOpen())
            .mapToLong(s -> AttendanceMath.workedSeconds(s, breaksById))
            .sum();
    long breakSeconds =
        periodSessions.stream()
            .filter(s -> !s.isOpen())
            .mapToLong(s -> AttendanceMath.breakSeconds(s, breaksById))
            .sum();
    Set<LocalDate> present =
        periodSessions.stream().map(AttendanceSession::getShiftDate).collect(Collectors.toSet());
    long daysPresent = present.size(); // headline: distinct shift-days with a session (unchanged)
    long lateLogins = periodSessions.stream().filter(AttendanceSession::isLate).count();

    // leaveDaysTotal / leavesByType are ALL calendar days (unchanged); only adherence uses working days.
    long casual = 0;
    long sick = 0;
    long unpaid = 0;
    long leaveDaysTotal = 0;
    long leaveRequests = 0;
    for (LeaveRequest lr : approvedLeaves) {
      long days = leaveDaysInRange(lr, start, end);
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

    // Adherence + absence: everything on the Mon–Fri working-day basis (see AttendanceCalendar).
    List<LocalDate> workingDays = AttendanceCalendar.workingDays(start, end);
    Set<LocalDate> leaveWorking = leaveWorkingDays(workingDays, approvedLeaves, start, end);
    long presentWorking = workingDays.stream().filter(present::contains).count(); // numerator (Mon–Fri)
    long expectedDays = workingDays.size() - leaveWorking.size(); // denominator (working − leave)
    Integer adherencePct =
        expectedDays <= 0 ? null : (int) Math.round((presentWorking * 100.0) / expectedDays);
    long unapprovedAbsences = countAbsences(workingDays, present, leaveWorking, today);

    return new EmployeeMonthSummary(
        period.monthLabel(),
        start.toString(),
        end.toString(),
        worked,
        breakSeconds,
        daysPresent,
        lateLogins,
        new LeavesByType(casual, sick, unpaid),
        leaveDaysTotal,
        leaveRequests,
        workingDays.size(),
        expectedDays,
        unapprovedAbsences,
        adherencePct,
        AttendanceCalendar.WORKING_DAYS_DEFINITION,
        new TimeComposition(worked, breakSeconds, null), // idle omitted in v1 (worked + break = gross)
        clockedInNow);
  }

  /** Distinct Mon–Fri dates covered by any approved leave, clipped to the window (adherence basis). */
  private static Set<LocalDate> leaveWorkingDays(
      List<LocalDate> workingDays, List<LeaveRequest> approvedLeaves, LocalDate periodStart, LocalDate periodEnd) {
    Set<LocalDate> working = new HashSet<>(workingDays);
    Set<LocalDate> out = new HashSet<>();
    for (LeaveRequest lr : approvedLeaves) {
      LocalDate start = lr.getStartDate().isBefore(periodStart) ? periodStart : lr.getStartDate();
      LocalDate end = lr.getEndDate().isAfter(periodEnd) ? periodEnd : lr.getEndDate();
      for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
        if (working.contains(d)) {
          out.add(d);
        }
      }
    }
    return out;
  }

  /**
   * Unapproved absences: PAST working days (strictly before {@code today}, IST) with no session and no
   * approved leave. Today and future working days are NEVER counted — their shift hasn't elapsed yet.
   */
  private static long countAbsences(
      List<LocalDate> workingDays, Set<LocalDate> present, Set<LocalDate> leaveWorking, LocalDate today) {
    return workingDays.stream()
        .filter(d -> d.isBefore(today))
        .filter(d -> !present.contains(d))
        .filter(d -> !leaveWorking.contains(d))
        .count();
  }

  /** Approved leave days inside the window: clipped to [periodStart, periodEnd], inclusive calendar days (v1). */
  private static long leaveDaysInRange(LeaveRequest lr, LocalDate periodStart, LocalDate periodEnd) {
    LocalDate start = lr.getStartDate().isBefore(periodStart) ? periodStart : lr.getStartDate();
    LocalDate end = lr.getEndDate().isAfter(periodEnd) ? periodEnd : lr.getEndDate();
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

  /** A resolved reporting window [start, end] inclusive; {@code monthLabel} is the YYYY-MM (null for a range). */
  private record Period(LocalDate start, LocalDate end, String monthLabel) {}

  /**
   * Resolve the reporting window: EITHER {@code month=YYYY-MM} (default = current shift-month) OR a custom
   * {@code from/to} range — mutually exclusive. Range rules: both present + parseable YYYY-MM-DD, from &lt;=
   * to, and a span of at most 366 days (inclusive); any violation -> 400. The monthly SERIES never uses this
   * (it is inherently monthly).
   */
  private static Period resolvePeriod(String month, String from, String to) {
    boolean hasMonth = month != null && !month.isBlank();
    boolean hasFrom = from != null && !from.isBlank();
    boolean hasTo = to != null && !to.isBlank();
    if (hasMonth && (hasFrom || hasTo)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "month and from/to are mutually exclusive");
    }
    if (hasFrom || hasTo) {
      if (!(hasFrom && hasTo)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "both from and to are required for a custom range");
      }
      LocalDate f = parseDate(from, "from");
      LocalDate t = parseDate(to, "to");
      if (t.isBefore(f)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be on or before to");
      }
      if (ChronoUnit.DAYS.between(f, t) > 365) { // 366 inclusive days == a diff of 365
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the range must not exceed 366 days");
      }
      return new Period(f, t, null);
    }
    YearMonth ym = parseMonth(month);
    return new Period(ym.atDay(1), ym.atEndOfMonth(), ym.toString());
  }

  /** {@code YYYY-MM-DD} (IST-agnostic calendar date). Invalid -> 400 naming the offending field. */
  private static LocalDate parseDate(String value, String field) {
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be YYYY-MM-DD");
    }
  }
}
