package com.ihrms.attendance;

import com.ihrms.attendance.dto.AttendanceDtos.AttendanceDayView;
import com.ihrms.attendance.dto.AttendanceDtos.AttendanceSessionView;
import com.ihrms.attendance.dto.AttendanceDtos.ClockStatusView;
import com.ihrms.attendance.dto.AttendanceDtos.MyAttendancePage;
import com.ihrms.attendance.dto.AttendanceDtos.TeamActivityEvent;
import com.ihrms.attendance.dto.AttendanceDtos.TeamActivityPage;
import com.ihrms.attendance.dto.AttendanceDtos.TeamAttendanceRow;
import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.AttendanceSessionRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Attendance (§8a): clock in / clock out, own history, and the Manager's team-scope views. The SERVER is
 * the time source (never a client timestamp); instants are UTC and all grouping/totals are computed in
 * Asia/Kolkata. A session is attributed to the IST day of its clock-in and its whole duration counts to
 * that day (not split at midnight); totals sum COMPLETED sessions only — an open session adds 0.
 */
@Service
public class AttendanceService {

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int DEFAULT_RANGE_DAYS = 14;
  private static final int MAX_RANGE_DAYS = 92;
  private static final List<String> CLOCK_ACTIONS =
      List.of("ATTENDANCE_CLOCK_IN", "ATTENDANCE_CLOCK_OUT");

  private final AttendanceSessionRepository sessions;
  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final AuditLogRepository auditLogs;
  private final AuthorizationService authz;
  private final AuditService audit;

  public AttendanceService(
      AttendanceSessionRepository sessions,
      EmployeeRepository employees,
      TeamRepository teams,
      AuditLogRepository auditLogs,
      AuthorizationService authz,
      AuditService audit) {
    this.sessions = sessions;
    this.employees = employees;
    this.teams = teams;
    this.auditLogs = auditLogs;
    this.authz = authz;
    this.audit = audit;
  }

  // --- Employee: clock in / out + status --------------------------------------

  /** Clock in. 409 if a session is already open (checked + DB partial-unique). Audited. */
  @Transactional
  public ClockStatusView clockIn(IhrmsPrincipal actor, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    if (sessions.findByEmployeeIdAndClockOutAtIsNull(me.getId()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already clocked in");
    }
    AttendanceSession session = new AttendanceSession();
    session.setEmployeeId(me.getId());
    session.setCompanyId(me.getCompanyId());
    session.setClockInAt(Instant.now()); // SERVER time, always
    try {
      sessions.saveAndFlush(session);
    } catch (DataIntegrityViolationException e) {
      // The partial-unique index caught a concurrent second open session — surface a clean 409, not 500.
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already clocked in");
    }
    ClockStatusView status = statusFor(me);
    audit.record(
        AuditActor.from(actor), "ATTENDANCE_CLOCK_IN", "AttendanceSession", session.getId(), Map.of(), ip);
    return status;
  }

  /** Clock out. 409 if no open session. Audited (with the completed duration). */
  @Transactional
  public ClockStatusView clockOut(IhrmsPrincipal actor, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    AttendanceSession open =
        sessions
            .findByEmployeeIdAndClockOutAtIsNull(me.getId())
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.CONFLICT, "You are not clocked in"));
    open.setClockOutAt(Instant.now()); // SERVER time
    sessions.save(open);
    long duration = durationSeconds(open);
    ClockStatusView status = statusFor(me);
    // Audit metadata values are STRINGS: a numeric (Long) value round-trips through the jsonb column on
    // Hibernate's dirty-check and is seen as changed, which would spuriously UPDATE the append-only row.
    audit.record(
        AuditActor.from(actor),
        "ATTENDANCE_CLOCK_OUT",
        "AttendanceSession",
        open.getId(),
        Map.of("durationSeconds", String.valueOf(duration)),
        ip);
    return status;
  }

  @Transactional(readOnly = true)
  public ClockStatusView status(IhrmsPrincipal actor) {
    return statusFor(requireCredentialedEmployee(actor));
  }

  private ClockStatusView statusFor(Employee me) {
    var open = sessions.findByEmployeeIdAndClockOutAtIsNull(me.getId());
    LocalDate today = LocalDate.now(IST);
    Instant weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(IST).toInstant();
    List<AttendanceSession> completedThisWeek =
        sessions.findByEmployeeIdAndClockOutAtIsNotNullAndClockInAtGreaterThanEqual(me.getId(), weekStart);
    long weekSeconds = completedThisWeek.stream().mapToLong(this::durationSeconds).sum();
    long todaySeconds =
        completedThisWeek.stream()
            .filter(s -> istDate(s.getClockInAt()).equals(today))
            .mapToLong(this::durationSeconds)
            .sum();
    return new ClockStatusView(
        open.isPresent(),
        open.map(s -> s.getClockInAt().toString()).orElse(null),
        todaySeconds,
        weekSeconds);
  }

  /** The caller's own history, day-grouped (Asia/Kolkata), paginated by session; total for the range. */
  @Transactional(readOnly = true)
  public MyAttendancePage myHistory(IhrmsPrincipal actor, String from, String to, Pageable pageable) {
    Employee me = requireCredentialedEmployee(actor);
    return historyFor(me.getId(), from, to, pageable);
  }

  // --- Manager: team-scope views ---------------------------------------------

  /** Roster of the Manager's team-scope employees with who's clocked in + today/period hours. */
  @Transactional(readOnly = true)
  public List<TeamAttendanceRow> teamSummary(IhrmsPrincipal.User manager, String from, String to) {
    List<Employee> scoped = scopedEmployees(manager);
    if (scoped.isEmpty()) {
      return List.of();
    }
    List<String> ids = scoped.stream().map(Employee::getId).toList();
    Set<String> clockedIn =
        sessions.findByEmployeeIdInAndClockOutAtIsNull(ids).stream()
            .map(AttendanceSession::getEmployeeId)
            .collect(Collectors.toSet());

    Instant[] range = resolveRange(from, to);
    LocalDate today = LocalDate.now(IST);
    List<AttendanceSession> windowCompleted =
        sessions
            .findByCompanyIdAndEmployeeIdInAndClockOutAtIsNotNullAndClockInAtGreaterThanEqualAndClockInAtLessThan(
                manager.companyId(), ids, range[0], range[1]);
    Map<String, Long> periodByEmp =
        windowCompleted.stream()
            .collect(Collectors.groupingBy(AttendanceSession::getEmployeeId, Collectors.summingLong(this::durationSeconds)));
    Map<String, Long> todayByEmp =
        windowCompleted.stream()
            .filter(s -> istDate(s.getClockInAt()).equals(today))
            .collect(Collectors.groupingBy(AttendanceSession::getEmployeeId, Collectors.summingLong(this::durationSeconds)));

    return scoped.stream()
        .map(
            e ->
                new TeamAttendanceRow(
                    e.getId(),
                    e.getEmployeeCode(),
                    e.getFullName(),
                    clockedIn.contains(e.getId()),
                    todayByEmp.getOrDefault(e.getId(), 0L),
                    periodByEmp.getOrDefault(e.getId(), 0L)))
        .sorted(Comparator.comparing(r -> r.fullName() == null ? "" : r.fullName().toLowerCase()))
        .collect(Collectors.toList());
  }

  /** One team-scope employee's day-grouped history (drill-down). Cross-scope employeeId -> 403. */
  @Transactional(readOnly = true)
  public MyAttendancePage teamEmployeeHistory(
      IhrmsPrincipal.User manager, String employeeId, String from, String to, Pageable pageable) {
    authz.assertCanAccessEmployee(manager, employeeId); // reuse the manager team-scope check (403 if outside)
    return historyFor(employeeId, from, to, pageable);
  }

  /** The Manager's pull-based activity feed: team-scope clock in/out events, newest first (audit-derived). */
  @Transactional(readOnly = true)
  public TeamActivityPage teamActivity(IhrmsPrincipal.User manager, Pageable pageable) {
    List<Employee> scoped = scopedEmployees(manager);
    if (scoped.isEmpty()) {
      return new TeamActivityPage(List.of(), pageable.getPageNumber(), pageable.getPageSize(), 0, 0);
    }
    List<String> ids = scoped.stream().map(Employee::getId).toList();
    Map<String, Employee> byId =
        scoped.stream().collect(Collectors.toMap(Employee::getId, e -> e, (a, b) -> a));
    Page<AuditLog> page =
        auditLogs.findByCompanyIdAndActionInAndActorIdInOrderByCreatedAtDesc(
            manager.companyId(), CLOCK_ACTIONS, ids, pageable);
    List<TeamActivityEvent> events =
        page.getContent().stream()
            .map(
                a -> {
                  Employee e = byId.get(a.getActorId());
                  return new TeamActivityEvent(
                      a.getActorId(),
                      e == null ? null : e.getEmployeeCode(),
                      e == null ? null : e.getFullName(),
                      "ATTENDANCE_CLOCK_IN".equals(a.getAction()) ? "IN" : "OUT",
                      a.getCreatedAt().toString());
                })
            .collect(Collectors.toList());
    return new TeamActivityPage(
        events, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  // --- Helpers ----------------------------------------------------------------

  private MyAttendancePage historyFor(String employeeId, String from, String to, Pageable pageable) {
    Instant[] range = resolveRange(from, to);
    Page<AttendanceSession> page =
        sessions.findByEmployeeIdAndClockInAtGreaterThanEqualAndClockInAtLessThan(
            employeeId, range[0], range[1], pageable);
    List<AttendanceDayView> days = groupByDay(page.getContent());
    long periodSeconds =
        sessions
            .findByEmployeeIdAndClockOutAtIsNotNullAndClockInAtGreaterThanEqualAndClockInAtLessThan(
                employeeId, range[0], range[1])
            .stream()
            .mapToLong(this::durationSeconds)
            .sum();
    return new MyAttendancePage(
        days, periodSeconds, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** Group a page of sessions into IST days (preserving the incoming newest-first order). */
  private List<AttendanceDayView> groupByDay(List<AttendanceSession> list) {
    Map<LocalDate, List<AttendanceSession>> byDay = new LinkedHashMap<>();
    for (AttendanceSession s : list) {
      byDay.computeIfAbsent(istDate(s.getClockInAt()), k -> new ArrayList<>()).add(s);
    }
    List<AttendanceDayView> out = new ArrayList<>();
    for (var entry : byDay.entrySet()) {
      long total =
          entry.getValue().stream().filter(s -> !s.isOpen()).mapToLong(this::durationSeconds).sum();
      out.add(
          new AttendanceDayView(
              entry.getKey().toString(),
              entry.getValue().stream().map(this::view).collect(Collectors.toList()),
              total));
    }
    return out;
  }

  private AttendanceSessionView view(AttendanceSession s) {
    return new AttendanceSessionView(
        s.getId(),
        s.getClockInAt().toString(),
        s.getClockOutAt() == null ? null : s.getClockOutAt().toString(),
        s.isOpen() ? null : durationSeconds(s));
  }

  private long durationSeconds(AttendanceSession s) {
    return s.isOpen() ? 0 : Duration.between(s.getClockInAt(), s.getClockOutAt()).getSeconds();
  }

  private LocalDate istDate(Instant instant) {
    return instant.atZone(IST).toLocalDate();
  }

  /** Parse from/to (yyyy-MM-dd, IST) into a [start, endExclusive) instant range with sane defaults + cap. */
  private Instant[] resolveRange(String from, String to) {
    LocalDate toDate = parseDate(to, LocalDate.now(IST));
    LocalDate fromDate = parseDate(from, toDate.minusDays(DEFAULT_RANGE_DAYS - 1L));
    if (fromDate.isAfter(toDate)) {
      fromDate = toDate;
    }
    if (fromDate.isBefore(toDate.minusDays(MAX_RANGE_DAYS))) {
      fromDate = toDate.minusDays(MAX_RANGE_DAYS); // cap the window
    }
    Instant start = fromDate.atStartOfDay(IST).toInstant();
    Instant end = toDate.plusDays(1).atStartOfDay(IST).toInstant(); // end of the 'to' day, exclusive
    return new Instant[] {start, end};
  }

  private LocalDate parseDate(String value, LocalDate fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return LocalDate.parse(value.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dates must be yyyy-MM-dd");
    }
  }

  private Employee requireCredentialedEmployee(IhrmsPrincipal actor) {
    if (!(actor instanceof IhrmsPrincipal.Employee principal)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Attendance is for employees");
    }
    Employee employee =
        employees
            .findById(principal.employeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
    if (employee.getMailAddress() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have attendance access");
    }
    return employee;
  }

  /** The Manager's team-scope employees — reuses teams-by-manager → HR ids → employees (same set he approves). */
  private List<Employee> scopedEmployees(IhrmsPrincipal.User manager) {
    String companyId = manager.companyId();
    if (companyId == null) {
      return List.of();
    }
    List<String> hrIds =
        teams.findByManagerUserId(manager.userId()).stream()
            .filter(t -> companyId.equals(t.getCompanyId()))
            .map(Team::getHrUserId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    if (hrIds.isEmpty()) {
      return List.of();
    }
    return employees.findByOnboardingHrIdIn(hrIds).stream()
        .filter(e -> companyId.equals(e.getCompanyId()))
        .collect(Collectors.toList());
  }
}
