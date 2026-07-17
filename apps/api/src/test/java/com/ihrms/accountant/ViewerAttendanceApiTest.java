package com.ihrms.accountant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.attendance.ShiftConfig;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.enums.LeaveType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.LeaveRequest;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AttendanceBreakRepository;
import com.ihrms.domain.repository.AttendanceSessionRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.LeaveRequestRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Read-only attendance analytics for the viewer roles (§8a/§2). Seeds a real month of sessions/breaks/
 * leaves — incl. an overnight session whose SHIFT-DAY lands in a different calendar month than its clock-in,
 * a late session, an open session, a break, and approved leaves clipped to the month — and asserts the live
 * metrics + the ACCOUNTS_ADMIN (any) / ACCOUNTANT (own-team-only) scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ViewerAttendanceApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired AttendanceSessionRepository sessions;
  @Autowired AttendanceBreakRepository breaks;
  @Autowired LeaveRequestRepository leaves;
  @Autowired JdbcTemplate jdbc;

  private String companyA;
  private Employee empA; // approved, team A (hrA)
  private Team teamA;
  private String adminToken; // ACCOUNTS_ADMIN
  private String accountantToken; // ACCOUNTANT of team A
  private Employee empB; // approved, company B / team B — foreign to team A's accountant
  private Team teamB;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"attendance_sessions\","
            + "\"attendance_breaks\",\"leave_requests\",\"audit_logs\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    String companyB = company("BBB");
    User hrA = user(companyA, UserRole.HR, "hra@a.test");
    User accA = user(companyA, UserRole.ACCOUNTANT, "acca@a.test");
    empA = approved(companyA, hrA.getId(), "AAA-EMP-000001", "Anita Approved");
    teamA = team(companyA, hrA.getId(), accA.getId());

    User hrB = user(companyB, UserRole.HR, "hrb@b.test");
    empB = approved(companyB, hrB.getId(), "BBB-EMP-000001", "Bob Approved");
    teamB = team(companyB, hrB.getId(), null);

    adminToken = token(user(null, UserRole.ACCOUNTS_ADMIN, "casey@books.test"), null);
    accountantToken = token(accA, teamA.getId());

    seedJanuaryForEmpA();
  }

  /**
   * Month = 2026-01. Sessions for empA:
   *  S1  Jan 10 19:00 -> Jan 11 03:00 IST (8h) with a 30-min break  -> worked 7.5h = 27000s
   *  S2  Jan 12 19:25 -> Jan 13 03:00 IST (7h35m) LATE, no break     -> worked 27300s, isLate
   *  S3  Feb 01 00:30 -> Feb 01 03:00 IST (2.5h), SHIFT-DAY Jan 31    -> worked 9000s (overnight boundary)
   *  S4  Jan 15 21:00 -> OPEN (not clocked out)                       -> worked 0, but makes clockedInNow true
   * Leaves (approved): CASUAL Jan 20-24 (5d), SICK Dec 30-Jan 02 (clip -> 2d), UNPAID Feb 05-06 (0 in Jan).
   */
  private void seedJanuaryForEmpA() {
    AttendanceSession s1 =
        session(empA, ist(2026, 1, 10, 19, 0), ist(2026, 1, 11, 3, 0), LocalDate.of(2026, 1, 10), false);
    brk(s1, ist(2026, 1, 10, 22, 0), ist(2026, 1, 10, 22, 30)); // 30-min break
    session(empA, ist(2026, 1, 12, 19, 25), ist(2026, 1, 13, 3, 0), LocalDate.of(2026, 1, 12), true);
    // Overnight boundary: clock-in is Feb 1 (calendar) but the shift-day is Jan 31.
    session(empA, ist(2026, 2, 1, 0, 30), ist(2026, 2, 1, 3, 0), LocalDate.of(2026, 1, 31), false);
    // Open session (no clock-out) -> contributes 0 worked, drives clockedInNow.
    openSession(empA, ist(2026, 1, 15, 21, 0), LocalDate.of(2026, 1, 15));

    leave(empA, LeaveType.CASUAL, LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 24));
    leave(empA, LeaveType.SICK, LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2));
    leave(empA, LeaveType.UNPAID, LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 6));
  }

  @Test
  void employeeSummary_workedExcludesBreaks_overnightInShiftMonth_leavesClipped_adherence_clockedInNow()
      throws Exception {
    JsonNode s = getJson("/accountant/employees/" + empA.getId() + "/attendance/summary?month=2026-01", adminToken);

    // Worked = 27000 + 27300 + 9000 + 0(open) = 63300 (breaks excluded; overnight counted in Jan).
    assertThat(s.get("workedSeconds").asLong()).isEqualTo(63300L);
    assertThat(s.get("breakSeconds").asLong()).isEqualTo(1800L);
    assertThat(s.get("daysPresent").asLong()).isEqualTo(4L); // Jan 10, 12, 15, 31
    assertThat(s.get("lateLogins").asLong()).isEqualTo(1L);
    assertThat(s.get("leavesByType").get("casual").asLong()).isEqualTo(5L);
    assertThat(s.get("leavesByType").get("sick").asLong()).isEqualTo(2L); // Dec 30-Jan 2 clipped to Jan 1-2
    assertThat(s.get("leavesByType").get("unpaid").asLong()).isEqualTo(0L);
    assertThat(s.get("leaveDaysTotal").asLong()).isEqualTo(7L);
    assertThat(s.get("leaveRequests").asLong()).isEqualTo(2L);
    assertThat(s.get("workingDays").asInt()).isEqualTo(31);
    assertThat(s.get("adherencePct").asInt()).isEqualTo(13); // round(4/31*100)
    // The donut split agrees with the headline worked/break; idle omitted in v1.
    assertThat(s.get("timeComposition").get("workedSeconds").asLong()).isEqualTo(63300L);
    assertThat(s.get("timeComposition").get("breakSeconds").asLong()).isEqualTo(1800L);
    assertThat(s.get("timeComposition").get("idleSeconds").isNull()).isTrue();
    assertThat(s.get("clockedInNow").asBoolean()).isTrue();

    // February: the overnight session (shift-day Jan 31) is NOT here; attendance is zero, unpaid leave shows.
    JsonNode feb = getJson("/accountant/employees/" + empA.getId() + "/attendance/summary?month=2026-02", adminToken);
    assertThat(feb.get("workedSeconds").asLong()).isZero();
    assertThat(feb.get("daysPresent").asLong()).isZero();
    assertThat(feb.get("leavesByType").get("unpaid").asLong()).isEqualTo(2L);
  }

  @Test
  void employeeMonthly_returnsSeriesWithCorrectMonthAndZerosForEmpty() throws Exception {
    JsonNode series = getJson("/accountant/employees/" + empA.getId() + "/attendance/monthly?months=24", adminToken);
    JsonNode months = series.get("months");
    // Last entry is the current shift-month (live).
    assertThat(months.get(months.size() - 1).get("month").asText())
        .isEqualTo(YearMonth.now(ShiftConfig.ZONE).toString());
    JsonNode jan = firstWhere(months, "month", "2026-01");
    assertThat(jan.get("workedSeconds").asLong()).isEqualTo(63300L);
    JsonNode mar = firstWhere(months, "month", "2026-03"); // no data -> zeros, not an error
    assertThat(mar.get("workedSeconds").asLong()).isZero();
    assertThat(mar.get("daysPresent").asLong()).isZero();
  }

  @Test
  void teamSummary_rollsUpAndBatchesTodaySnapshot() throws Exception {
    JsonNode t = getJson("/accountant/teams/" + teamA.getId() + "/attendance/summary?month=2026-01", adminToken);
    assertThat(t.get("employeeCount").asInt()).isEqualTo(1);
    assertThat(t.get("totalLateThisMonth").asLong()).isEqualTo(1L);
    assertThat(t.get("clockedInNow").asLong()).isEqualTo(1L); // the open session
    assertThat(t.get("presentToday").asLong()).isZero(); // seeded sessions are all in January
    assertThat(t.get("onLeaveToday").asLong()).isZero();
    JsonNode row = t.get("employees").get(0);
    assertThat(row.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(row.get("workedSeconds").asLong()).isEqualTo(63300L);
    assertThat(row.get("lateLogins").asLong()).isEqualTo(1L);
    assertThat(row.get("leaveDaysTotal").asLong()).isEqualTo(7L);
    assertThat(row.get("clockedInNow").asBoolean()).isTrue();
  }

  @Test
  void scoping_accountsAdminAny_accountantOwnTeamOnly() throws Exception {
    // ACCOUNTANT: own employee/team -> ok.
    mvc.perform(get("/accountant/employees/" + empA.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isOk());
    mvc.perform(get("/accountant/teams/" + teamA.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isOk());
    // ACCOUNTANT: foreign employee + foreign team -> 404 (never widened).
    mvc.perform(get("/accountant/employees/" + empB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/accountant/teams/" + teamB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isNotFound());
    // ACCOUNTS_ADMIN: any company's employee + team -> ok.
    mvc.perform(get("/accountant/employees/" + empB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    mvc.perform(get("/accountant/teams/" + teamB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode getJson(String path, String token) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static JsonNode firstWhere(JsonNode array, String field, String value) {
    for (JsonNode n : array) {
      if (value.equals(n.get(field).asText())) {
        return n;
      }
    }
    throw new AssertionError("no element with " + field + "=" + value);
  }

  private static Instant ist(int y, int mo, int d, int h, int mi) {
    return LocalDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi)).atZone(ShiftConfig.ZONE).toInstant();
  }

  private AttendanceSession session(
      Employee e, Instant in, Instant out, LocalDate shiftDate, boolean late) {
    AttendanceSession s = new AttendanceSession();
    s.setEmployeeId(e.getId());
    s.setCompanyId(e.getCompanyId());
    s.setClockInAt(in);
    s.setClockOutAt(out);
    s.setShiftDate(shiftDate);
    s.setLate(late);
    return sessions.save(s);
  }

  private void openSession(Employee e, Instant in, LocalDate shiftDate) {
    AttendanceSession s = new AttendanceSession();
    s.setEmployeeId(e.getId());
    s.setCompanyId(e.getCompanyId());
    s.setClockInAt(in);
    s.setShiftDate(shiftDate);
    s.setLate(false);
    sessions.save(s);
  }

  private void brk(AttendanceSession s, Instant start, Instant end) {
    AttendanceBreak b = new AttendanceBreak();
    b.setSessionId(s.getId());
    b.setCompanyId(s.getCompanyId());
    b.setBreakStartAt(start);
    b.setBreakEndAt(end);
    breaks.save(b);
  }

  private void leave(Employee e, LeaveType type, LocalDate start, LocalDate end) {
    LeaveRequest lr = new LeaveRequest();
    lr.setEmployeeId(e.getId());
    lr.setCompanyId(e.getCompanyId());
    lr.setManagerUserId("mgr-" + e.getId());
    lr.setStartDate(start);
    lr.setEndDate(end);
    lr.setLeaveType(type);
    lr.setReason("seed");
    lr.setStatus(LeaveStatus.APPROVED);
    leaves.save(lr);
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    c.setMailDomain(code.toLowerCase());
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Employee approved(String companyId, String hrId, String code, String name) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName(name);
    e.setEmail(code.toLowerCase() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private Team team(String companyId, String hrId, String accountantId) {
    Team t = new Team();
    t.setName("Team " + companyId.substring(0, 4));
    t.setCompanyId(companyId);
    t.setHrUserId(hrId);
    t.setAccountantUserId(accountantId);
    return teams.save(t);
  }

  private String token(User u, String teamId) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), teamId));
  }
}
