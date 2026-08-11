package com.ihrms.attendance;

import com.ihrms.auth.MailService;
import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AttendanceBreakRepository;
import com.ihrms.domain.repository.AttendanceSessionRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.push.PushService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled "long open break" HR alert (§8a). Every {@code app.break-alert-scan-ms} (default 5 min) it finds
 * breaks that are STILL OPEN and started more than {@code app.break-alert-minutes} (default 35, env
 * {@code BREAK_ALERT_MINUTES}) ago and have not been alerted yet, and notifies each employee's onboarding HR
 * ONCE — a durable notification (in {@link BreakAlertService}, which also stamps {@code alertSentAt} to dedupe)
 * plus best-effort push + email fired AFTER that commits.
 *
 * <p>Edge cases: an employee who never ends a break is alerted once (the stamp is not re-scanned); overnight
 * breaks need no special handling (aging is a plain {@code breakStartAt} instant vs now); an employee whose HR
 * is unresolved is stamped + skipped (logged, no error); many concurrent long breaks are batch-resolved
 * (session → employee → HR) so the scan is a handful of queries, never N+1.
 *
 * <p>Single-instance only: this simple {@code @Scheduled} would double-fire under a multi-instance deployment —
 * that would need a shared lock (ShedLock / advisory lock), noted as a future hardening in ARCHITECTURE.md.
 */
@Component
public class BreakAlertScanner {

  private static final Logger log = LoggerFactory.getLogger(BreakAlertScanner.class);

  private final AttendanceBreakRepository breaks;
  private final AttendanceSessionRepository sessions;
  private final EmployeeRepository employees;
  private final UserRepository users;
  private final BreakAlertService alertService;
  private final PushService push;
  private final MailService mail;

  @Value("${app.break-alert-minutes:35}")
  private int alertMinutes;

  public BreakAlertScanner(
      AttendanceBreakRepository breaks,
      AttendanceSessionRepository sessions,
      EmployeeRepository employees,
      UserRepository users,
      BreakAlertService alertService,
      PushService push,
      MailService mail) {
    this.breaks = breaks;
    this.sessions = sessions;
    this.employees = employees;
    this.users = users;
    this.alertService = alertService;
    this.push = push;
    this.mail = mail;
  }

  /**
   * The periodic scan. {@code initialDelay == fixedDelay} so it never fires at startup (keeps test contexts
   * quiet — tests call {@link #scan()} directly). Public so it is unit/integration-testable without waiting.
   */
  @Scheduled(
      fixedDelayString = "${app.break-alert-scan-ms:300000}",
      initialDelayString = "${app.break-alert-scan-ms:300000}")
  public void scan() {
    int minutes = Math.max(1, alertMinutes);
    Instant now = Instant.now();
    List<AttendanceBreak> candidates =
        breaks.findByBreakEndAtIsNullAndAlertSentAtIsNullAndBreakStartAtBefore(
            now.minus(Duration.ofMinutes(minutes)));
    if (candidates.isEmpty()) {
      return;
    }

    // Batch-resolve break → session → employee → onboarding HR (no N+1).
    Set<String> sessionIds =
        candidates.stream().map(AttendanceBreak::getSessionId).collect(Collectors.toSet());
    Map<String, AttendanceSession> sessionById =
        sessions.findAllById(sessionIds).stream()
            .collect(Collectors.toMap(AttendanceSession::getId, Function.identity()));
    Set<String> employeeIds =
        sessionById.values().stream()
            .map(AttendanceSession::getEmployeeId)
            .collect(Collectors.toSet());
    Map<String, Employee> employeeById =
        employees.findAllById(employeeIds).stream()
            .collect(Collectors.toMap(Employee::getId, Function.identity()));
    Set<String> hrIds =
        employeeById.values().stream()
            .map(Employee::getOnboardingHrId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<String, User> hrById =
        users.findAllById(hrIds).stream()
            .collect(Collectors.toMap(User::getId, Function.identity()));

    int notified = 0;
    int skipped = 0;
    for (AttendanceBreak br : candidates) {
      AttendanceSession session = sessionById.get(br.getSessionId());
      Employee employee = session == null ? null : employeeById.get(session.getEmployeeId());
      User hr =
          (employee == null || employee.getOnboardingHrId() == null)
              ? null
              : hrById.get(employee.getOnboardingHrId());

      // Stamp alertSentAt (+ durable notification when HR resolved) in its OWN tx — never re-alert this break.
      alertService.recordAlert(
          br.getId(), hr == null ? null : hr.getId(), employee == null ? null : employee.getId());

      if (employee == null || hr == null) {
        log.info(
            "Long-break alert skipped for break {} — employee/HR unresolved (stamped to avoid re-scan).",
            br.getId());
        skipped++;
        continue;
      }

      long elapsed = Duration.between(br.getBreakStartAt(), now).toMinutes();
      String who = displayName(employee);
      // Push + email are best-effort and fired AFTER the stamp commits (outside its transaction).
      try {
        push.sendToPrincipal(
            PushService.PrincipalRef.forUser(hr.getId(), employee.getCompanyId()),
            "Long break",
            who + " has been on break for " + elapsed + " minutes.",
            "/hr/employees/" + employee.getId());
      } catch (Exception e) {
        log.warn("Break-alert push failed for {}: {}", br.getId(), e.toString());
      }
      try {
        mail.sendLongBreakAlert(hr.getEmail(), who, elapsed, employee.getCompanyId());
      } catch (Exception e) {
        log.warn("Break-alert email failed for {}: {}", br.getId(), e.toString());
      }
      notified++;
    }
    log.info(
        "Long-break scan (> {}m): {} candidate(s), {} HR alerts, {} skipped.",
        minutes,
        candidates.size(),
        notified,
        skipped);
  }

  private static String displayName(Employee e) {
    if (e.getFullName() != null && !e.getFullName().isBlank()) {
      return e.getFullName();
    }
    return e.getEmployeeCode() != null ? e.getEmployeeCode() : "An employee";
  }
}
