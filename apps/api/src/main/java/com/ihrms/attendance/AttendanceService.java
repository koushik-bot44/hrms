package com.ihrms.attendance;

import com.ihrms.attendance.dto.AttendanceDtos.AttendanceBreakView;
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
import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.AttendanceBreakRepository;
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
import java.util.Optional;
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
 * Attendance (§8a v2): clock in / clock out with a fixed overnight shift (19:00→04:00, late after 19:20),
 * breaks excluded from worked hours, own history, and the Manager's team-scope views. The SERVER is the
 * time source (never a client timestamp); instants are UTC and all grouping/totals are in Asia/Kolkata.
 * A session is attributed to its SHIFT-DAY (persisted); worked time = completed sessions' duration minus
 * their break time. Shift constants + the shift-day/late rules live in {@link ShiftConfig}.
 */
@Service
public class AttendanceService {

  private static final ZoneId IST = ShiftConfig.ZONE;
  private static final int DEFAULT_RANGE_DAYS = 14;
  private static final int MAX_RANGE_DAYS = 92;
  private static final List<String> ACTIVITY_ACTIONS =
      List.of(
          "ATTENDANCE_CLOCK_IN",
          "ATTENDANCE_CLOCK_OUT",
          "ATTENDANCE_BREAK_START",
          "ATTENDANCE_BREAK_END");

  private final AttendanceSessionRepository sessions;
  private final AttendanceBreakRepository breaks;
  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final AuditLogRepository auditLogs;
  private final AuthorizationService authz;
  private final AuditService audit;

  public AttendanceService(
      AttendanceSessionRepository sessions,
      AttendanceBreakRepository breaks,
      EmployeeRepository employees,
      TeamRepository teams,
      AuditLogRepository auditLogs,
      AuthorizationService authz,
      AuditService audit) {
    this.sessions = sessions;
    this.breaks = breaks;
    this.employees = employees;
    this.teams = teams;
    this.auditLogs = auditLogs;
    this.authz = authz;
    this.audit = audit;
  }

  // --- Employee: clock in / out + breaks + status -----------------------------

  /** Clock in. 409 if a session is already open (checked + DB partial-unique). Late/shift-day persisted. */
  @Transactional
  public ClockStatusView clockIn(IhrmsPrincipal actor, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    if (sessions.findByEmployeeIdAndClockOutAtIsNull(me.getId()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already clocked in");
    }
    Instant now = Instant.now(); // SERVER time, always
    LocalDate shiftDate = ShiftConfig.shiftDateOf(now);
    boolean firstOfShiftDay = !sessions.existsByEmployeeIdAndShiftDate(me.getId(), shiftDate);

    AttendanceSession session = new AttendanceSession();
    session.setEmployeeId(me.getId());
    session.setCompanyId(me.getCompanyId());
    session.setClockInAt(now);
    session.setShiftDate(shiftDate);
    // The FIRST clock-in of a shift-day after 19:20 IST is late; persisted so the Manager needn't recompute.
    if (firstOfShiftDay && ShiftConfig.isLate(now, shiftDate)) {
      session.setLate(true);
      session.setLateMinutes((int) ShiftConfig.lateMinutes(now, shiftDate));
    }
    try {
      sessions.saveAndFlush(session);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already clocked in");
    }
    ClockStatusView status = statusFor(me);
    audit.record(
        AuditActor.from(actor), "ATTENDANCE_CLOCK_IN", "AttendanceSession", session.getId(), Map.of(), ip);
    return status;
  }

  /** Clock out. 409 if no open session, or if a break is still open. Audited (with the completed duration). */
  @Transactional
  public ClockStatusView clockOut(IhrmsPrincipal actor, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    AttendanceSession open = requireOpenSession(me);
    if (breaks.findBySessionIdAndBreakEndAtIsNull(open.getId()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "End your break before clocking out");
    }
    open.setClockOutAt(Instant.now()); // SERVER time
    sessions.save(open);
    long duration = Duration.between(open.getClockInAt(), open.getClockOutAt()).getSeconds();
    ClockStatusView status = statusFor(me);
    // Audit metadata values are STRINGS: a numeric jsonb value re-dirties the append-only row on flush.
    audit.record(
        AuditActor.from(actor),
        "ATTENDANCE_CLOCK_OUT",
        "AttendanceSession",
        open.getId(),
        Map.of("durationSeconds", String.valueOf(duration)),
        ip);
    return status;
  }

  /** Start a break within the open session. 409 if not clocked in, or a break is already open. */
  @Transactional
  public ClockStatusView startBreak(IhrmsPrincipal actor, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    AttendanceSession open = requireOpenSession(me);
    if (breaks.findBySessionIdAndBreakEndAtIsNull(open.getId()).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already on a break");
    }
    AttendanceBreak b = new AttendanceBreak();
    b.setSessionId(open.getId());
    b.setCompanyId(me.getCompanyId());
    b.setBreakStartAt(Instant.now());
    try {
      breaks.saveAndFlush(b);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You are already on a break");
    }
    ClockStatusView status = statusFor(me);
    audit.record(
        AuditActor.from(actor), "ATTENDANCE_BREAK_START", "AttendanceBreak", b.getId(), Map.of(), ip);
    return status;
  }

  /** End the open break. 409 if not clocked in, or no break is open. Audited (with the break duration). */
  @Transactional
  public ClockStatusView endBreak(IhrmsPrincipal actor, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    AttendanceSession open = requireOpenSession(me);
    AttendanceBreak b =
        breaks
            .findBySessionIdAndBreakEndAtIsNull(open.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "You are not on a break"));
    b.setBreakEndAt(Instant.now());
    breaks.save(b);
    long duration = Duration.between(b.getBreakStartAt(), b.getBreakEndAt()).getSeconds();
    ClockStatusView status = statusFor(me);
    audit.record(
        AuditActor.from(actor),
        "ATTENDANCE_BREAK_END",
        "AttendanceBreak",
        b.getId(),
        Map.of("durationSeconds", String.valueOf(duration)),
        ip);
    return status;
  }

  @Transactional(readOnly = true)
  public ClockStatusView status(IhrmsPrincipal actor) {
    return statusFor(requireCredentialedEmployee(actor));
  }

  private ClockStatusView statusFor(Employee me) {
    Optional<AttendanceSession> open = sessions.findByEmployeeIdAndClockOutAtIsNull(me.getId());
    Optional<AttendanceBreak> openBreak =
        open.flatMap(s -> breaks.findBySessionIdAndBreakEndAtIsNull(s.getId()));
    LocalDate currentShiftDay = ShiftConfig.shiftDateOf(Instant.now());
    boolean isLateToday =
        sessions.existsByEmployeeIdAndShiftDateAndLateTrue(me.getId(), currentShiftDay);

    // "This week" spans shift-days from the currentShiftDay's Monday; a shift-day's earliest clock-in is at
    // 04:00 IST of that day, so bounding clock-in at Monday 04:00 IST captures exactly that week's sessions.
    Instant weekStart =
        currentShiftDay
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atTime(ShiftConfig.SHIFT_END)
            .atZone(IST)
            .toInstant();
    List<AttendanceSession> completedThisWeek =
        sessions.findByEmployeeIdAndClockOutAtIsNotNullAndClockInAtGreaterThanEqual(me.getId(), weekStart);
    Map<String, List<AttendanceBreak>> byId = breaksBySession(completedThisWeek);
    long weekSeconds = completedThisWeek.stream().mapToLong(s -> workedSeconds(s, byId)).sum();
    long todaySeconds =
        completedThisWeek.stream()
            .filter(s -> currentShiftDay.equals(s.getShiftDate()))
            .mapToLong(s -> workedSeconds(s, byId))
            .sum();
    return new ClockStatusView(
        open.isPresent(),
        open.map(s -> s.getClockInAt().toString()).orElse(null),
        openBreak.isPresent(),
        openBreak.map(b -> b.getBreakStartAt().toString()).orElse(null),
        isLateToday,
        todaySeconds,
        weekSeconds);
  }

  /** The caller's own history, grouped by SHIFT-DAY, paginated by session; worked total for the range. */
  @Transactional(readOnly = true)
  public MyAttendancePage myHistory(IhrmsPrincipal actor, String from, String to, Pageable pageable) {
    Employee me = requireCredentialedEmployee(actor);
    return historyFor(me.getId(), from, to, pageable);
  }

  // --- Manager: team-scope views ---------------------------------------------

  /** Roster of the Manager's team-scope employees: state (clocked-in / on-break / late-today) + worked hours. */
  @Transactional(readOnly = true)
  public List<TeamAttendanceRow> teamSummary(IhrmsPrincipal.User manager, String from, String to) {
    List<Employee> scoped = scopedEmployees(manager);
    if (scoped.isEmpty()) {
      return List.of();
    }
    List<String> ids = scoped.stream().map(Employee::getId).toList();

    List<AttendanceSession> openSessions = sessions.findByEmployeeIdInAndClockOutAtIsNull(ids);
    Set<String> clockedIn =
        openSessions.stream().map(AttendanceSession::getEmployeeId).collect(Collectors.toSet());
    Map<String, String> openSessionToEmp =
        openSessions.stream()
            .collect(Collectors.toMap(AttendanceSession::getId, AttendanceSession::getEmployeeId, (a, b) -> a));
    Set<String> onBreak =
        breaks.findBySessionIdInOrderByBreakStartAtAsc(openSessionToEmp.keySet()).stream()
            .filter(AttendanceBreak::isOpen)
            .map(b -> openSessionToEmp.get(b.getSessionId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

    LocalDate currentShiftDay = ShiftConfig.shiftDateOf(Instant.now());
    Instant[] range = resolveRange(from, to);
    List<AttendanceSession> windowCompleted =
        sessions
            .findByCompanyIdAndEmployeeIdInAndClockOutAtIsNotNullAndClockInAtGreaterThanEqualAndClockInAtLessThan(
                manager.companyId(), ids, range[0], range[1]);
    Map<String, List<AttendanceBreak>> breaksById = breaksBySession(windowCompleted);
    Map<String, Long> periodByEmp =
        windowCompleted.stream()
            .collect(
                Collectors.groupingBy(
                    AttendanceSession::getEmployeeId, Collectors.summingLong(s -> workedSeconds(s, breaksById))));
    Map<String, Long> todayByEmp =
        windowCompleted.stream()
            .filter(s -> currentShiftDay.equals(s.getShiftDate()))
            .collect(
                Collectors.groupingBy(
                    AttendanceSession::getEmployeeId, Collectors.summingLong(s -> workedSeconds(s, breaksById))));

    return scoped.stream()
        .map(
            e ->
                new TeamAttendanceRow(
                    e.getId(),
                    e.getEmployeeCode(),
                    e.getFullName(),
                    clockedIn.contains(e.getId()),
                    onBreak.contains(e.getId()),
                    sessions.existsByEmployeeIdAndShiftDateAndLateTrue(e.getId(), currentShiftDay),
                    todayByEmp.getOrDefault(e.getId(), 0L),
                    periodByEmp.getOrDefault(e.getId(), 0L)))
        .sorted(Comparator.comparing(r -> r.fullName() == null ? "" : r.fullName().toLowerCase()))
        .collect(Collectors.toList());
  }

  /** One team-scope employee's shift-day history (drill-down). Cross-scope employeeId -> 403. */
  @Transactional(readOnly = true)
  public MyAttendancePage teamEmployeeHistory(
      IhrmsPrincipal.User manager, String employeeId, String from, String to, Pageable pageable) {
    authz.assertCanAccessEmployee(manager, employeeId); // reuse the manager team-scope check (403 if outside)
    return historyFor(employeeId, from, to, pageable);
  }

  /** The Manager's pull-based activity feed: team-scope clock in/out + break events, newest first. */
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
            manager.companyId(), ACTIVITY_ACTIONS, ids, pageable);
    List<TeamActivityEvent> events =
        page.getContent().stream()
            .map(
                a -> {
                  Employee e = byId.get(a.getActorId());
                  return new TeamActivityEvent(
                      a.getActorId(),
                      e == null ? null : e.getEmployeeCode(),
                      e == null ? null : e.getFullName(),
                      activityType(a.getAction()),
                      a.getCreatedAt().toString());
                })
            .collect(Collectors.toList());
    return new TeamActivityPage(
        events, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  private static String activityType(String action) {
    return switch (action) {
      case "ATTENDANCE_CLOCK_IN" -> "IN";
      case "ATTENDANCE_CLOCK_OUT" -> "OUT";
      case "ATTENDANCE_BREAK_START" -> "BREAK_START";
      case "ATTENDANCE_BREAK_END" -> "BREAK_END";
      default -> action;
    };
  }

  // --- Helpers ----------------------------------------------------------------

  private MyAttendancePage historyFor(String employeeId, String from, String to, Pageable pageable) {
    Instant[] range = resolveRange(from, to);
    Page<AttendanceSession> page =
        sessions.findByEmployeeIdAndClockInAtGreaterThanEqualAndClockInAtLessThan(
            employeeId, range[0], range[1], pageable);
    Map<String, List<AttendanceBreak>> pageBreaks = breaksBySession(page.getContent());
    List<AttendanceDayView> days = groupByShiftDay(page.getContent(), pageBreaks);

    List<AttendanceSession> completed =
        sessions.findByEmployeeIdAndClockOutAtIsNotNullAndClockInAtGreaterThanEqualAndClockInAtLessThan(
            employeeId, range[0], range[1]);
    Map<String, List<AttendanceBreak>> completedBreaks = breaksBySession(completed);
    long periodSeconds = completed.stream().mapToLong(s -> workedSeconds(s, completedBreaks)).sum();
    return new MyAttendancePage(
        days, periodSeconds, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  /** Group a page of sessions into SHIFT-DAYs (preserving the incoming newest-first order). */
  private List<AttendanceDayView> groupByShiftDay(
      List<AttendanceSession> list, Map<String, List<AttendanceBreak>> byId) {
    Map<LocalDate, List<AttendanceSession>> byDay = new LinkedHashMap<>();
    for (AttendanceSession s : list) {
      byDay.computeIfAbsent(s.getShiftDate(), k -> new ArrayList<>()).add(s);
    }
    List<AttendanceDayView> out = new ArrayList<>();
    for (var entry : byDay.entrySet()) {
      long total =
          entry.getValue().stream().filter(s -> !s.isOpen()).mapToLong(s -> workedSeconds(s, byId)).sum();
      boolean late = entry.getValue().stream().anyMatch(AttendanceSession::isLate);
      out.add(
          new AttendanceDayView(
              entry.getKey().toString(),
              entry.getValue().stream().map(s -> view(s, byId)).collect(Collectors.toList()),
              total,
              late));
    }
    return out;
  }

  private AttendanceSessionView view(AttendanceSession s, Map<String, List<AttendanceBreak>> byId) {
    List<AttendanceBreak> bs = byId.getOrDefault(s.getId(), List.of());
    return new AttendanceSessionView(
        s.getId(),
        s.getClockInAt().toString(),
        s.getClockOutAt() == null ? null : s.getClockOutAt().toString(),
        s.isOpen() ? null : workedSeconds(s, byId),
        s.isLate(),
        s.getLateMinutes(),
        bs.stream().map(this::breakView).collect(Collectors.toList()));
  }

  private AttendanceBreakView breakView(AttendanceBreak b) {
    return new AttendanceBreakView(
        b.getId(),
        b.getBreakStartAt().toString(),
        b.getBreakEndAt() == null ? null : b.getBreakEndAt().toString(),
        b.isOpen() ? null : Duration.between(b.getBreakStartAt(), b.getBreakEndAt()).getSeconds());
  }

  private Map<String, List<AttendanceBreak>> breaksBySession(List<AttendanceSession> list) {
    List<String> ids = list.stream().map(AttendanceSession::getId).toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    return breaks.findBySessionIdInOrderByBreakStartAtAsc(ids).stream()
        .collect(Collectors.groupingBy(AttendanceBreak::getSessionId));
  }

  /** Worked seconds for a completed session = its duration minus the sum of its COMPLETED breaks. */
  private long workedSeconds(AttendanceSession s, Map<String, List<AttendanceBreak>> byId) {
    // Delegated to the shared computation so worked time is never forked (§8a).
    return AttendanceMath.workedSeconds(s, byId);
  }

  private AttendanceSession requireOpenSession(Employee me) {
    return sessions
        .findByEmployeeIdAndClockOutAtIsNull(me.getId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "You are not clocked in"));
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
