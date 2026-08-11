package com.ihrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AttendanceBreakRepository;
import com.ihrms.domain.repository.AttendanceSessionRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.email.Mailer;
import com.ihrms.email.OutboundEmail;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scheduled long-open-break HR alert (§8a). Threshold pinned to 10 minutes via properties (proving it is
 * env-configurable — a 15-minute break would NOT fire at the default 35) and the scheduler disabled (huge
 * interval) so ONLY the explicit {@link BreakAlertScanner#scan()} calls run. Uses the spied {@link Mailer}
 * (DevLogMailer) — zero network. Each open break gets its OWN employee/session to respect the "one open
 * session per employee" partial-unique index. Gated on a local Postgres.
 */
@SpringBootTest(properties = {"app.break-alert-minutes=10", "app.break-alert-scan-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class BreakAlertScannerTest {

  @Autowired BreakAlertScanner scanner;
  @Autowired BreakAlertService alertService;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired AttendanceSessionRepository sessions;
  @Autowired AttendanceBreakRepository breaks;
  @Autowired NotificationRepository notifications;
  @Autowired JdbcTemplate jdbc;

  @SpyBean Mailer mailer;

  private String companyId;
  private User hr;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"attendance_sessions\",\"attendance_breaks\","
            + "\"notifications\",\"audit_logs\" RESTART IDENTITY CASCADE");
    Company c = new Company();
    c.setName("Acme Inc");
    c.setCode("ACME");
    companyId = companies.save(c).getId();
    hr = user(UserRole.HR, "hana.hr@acme.test");
  }

  // --- an open break past the threshold alerts the HR exactly once --------------

  @Test
  void openBreakPastThresholdAlertsTheHrOnceAndDedupes() {
    Employee emp = employee("Alex Doe", hr.getId());
    AttendanceBreak br = openBreak(emp, minutesAgo(15)); // 15 > 10 (would NOT fire at the default 35)

    scanner.scan();

    // Durable notification to the onboarding HR.
    List<Notification> notes = notifications.findAll();
    assertThat(notes).hasSize(1);
    assertThat(notes.get(0).getRecipientUserId()).isEqualTo(hr.getId());
    assertThat(notes.get(0).getType()).isEqualTo(NotificationType.EMPLOYEE_LONG_BREAK);
    assertThat(notes.get(0).getEmployeeId()).isEqualTo(emp.getId());

    // Break stamped (dedupe) + email to HR naming the employee.
    assertThat(breaks.findById(br.getId()).orElseThrow().getAlertSentAt()).isNotNull();
    ArgumentCaptor<OutboundEmail> captor = ArgumentCaptor.forClass(OutboundEmail.class);
    verify(mailer, timeout(3000).times(1)).send(captor.capture());
    assertThat(captor.getValue().to()).isEqualTo(hr.getEmail());
    assertThat(captor.getValue().text()).contains("Alex Doe");

    // A second scan does NOT re-notify (alertSentAt filters it out).
    scanner.scan();
    assertThat(notifications.findAll()).hasSize(1);
    verify(mailer, times(1)).send(any()); // still exactly one email
  }

  // --- a short break, and an ended break, do NOT alert --------------------------

  @Test
  void shortBreakAndEndedBreakDoNotAlert() {
    openBreak(employee("Short Break", hr.getId()), minutesAgo(5)); // under the 10-minute threshold

    AttendanceBreak ended = openBreak(employee("Ended Break", hr.getId()), minutesAgo(20));
    ended.setBreakEndAt(minutesAgo(2)); // ended before this scan
    breaks.save(ended);

    scanner.scan();

    assertThat(notifications.findAll()).isEmpty();
    verify(mailer, never()).send(any());
  }

  // --- an unresolved HR is stamped + skipped (no durable notification, no error) --

  @Test
  void unresolvedHrIsStampedAndSkipped() {
    Employee emp = employee("Alex Doe", hr.getId());
    AttendanceBreak br = openBreak(emp, minutesAgo(15));
    // Exercise the defensive path directly (the schema's NOT-NULL FK makes a truly unset HR unreachable
    // end-to-end): recording with a null HR stamps the break but writes NO notification.
    alertService.recordAlert(br.getId(), null, emp.getId());

    assertThat(breaks.findById(br.getId()).orElseThrow().getAlertSentAt()).isNotNull();
    assertThat(notifications.findAll()).isEmpty();

    // The scan now skips it (already stamped) — no notification, no error.
    scanner.scan();
    assertThat(notifications.findAll()).isEmpty();
  }

  // --- helpers ------------------------------------------------------------------

  private static Instant minutesAgo(long m) {
    return Instant.now().minus(Duration.ofMinutes(m));
  }

  /** A fresh session (open) + break (open) for the given employee, so no two sessions collide on the index. */
  private AttendanceBreak openBreak(Employee emp, Instant startedAt) {
    AttendanceSession s = new AttendanceSession();
    s.setEmployeeId(emp.getId());
    s.setCompanyId(companyId);
    s.setClockInAt(startedAt.minus(Duration.ofMinutes(5)));
    s.setShiftDate(LocalDate.now());
    sessions.save(s);

    AttendanceBreak b = new AttendanceBreak();
    b.setSessionId(s.getId());
    b.setCompanyId(companyId);
    b.setBreakStartAt(startedAt);
    return breaks.save(b);
  }

  private User user(UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name());
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Employee employee(String fullName, String hrId) {
    Employee e = new Employee();
    e.setFullName(fullName);
    e.setEmail("emp-" + UUID.randomUUID() + "@ext.test");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }
}
