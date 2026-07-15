package com.ihrms.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Attendance (§8a v2): clock in/out (one open session, 409s), breaks (one open, worked excludes breaks,
 * clock-out guard, DB partial-unique), status/totals, credentialed-only access, and the Manager's
 * team-scoped roster / drill-down / activity feed. The shift-day + late RULE is unit-tested in
 * {@link ShiftConfigTest}. Gated on a local Postgres.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AttendanceApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired JdbcTemplate jdbc;

  private String acme;
  private User hr;
  private User manager;
  private User otherManager;
  private Employee emp; // credentialed, onboarded by hr, on manager's team
  private Employee uncredentialed; // approved but no mailbox
  private Employee outOfScope; // another HR/manager's team

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"attendance_breaks\","
            + "\"attendance_sessions\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");
    hr = user(acme, UserRole.HR, "hr@acme");
    manager = user(acme, UserRole.MANAGER, "mgr@acme");
    User hr2 = user(acme, UserRole.HR, "hr2@acme");
    otherManager = user(acme, UserRole.MANAGER, "mgr2@acme");
    team(acme, hr.getId(), manager.getId());
    team(acme, hr2.getId(), otherManager.getId());

    emp = employee(acme, hr, "arjun@acme");
    uncredentialed = employee(acme, hr, null);
    outOfScope = employee(acme, hr2, "meera@acme");
  }

  // --- Clock in / out + status ----------------------------------------------

  @Test
  void clockInThenOutWithTheOneOpenSessionRules() throws Exception {
    String tok = empToken(emp);
    JsonNode s1 = body(clockIn(tok).andExpect(status().isOk()));
    assertThat(s1.get("open").asBoolean()).isTrue();
    assertThat(s1.get("openSince").asText()).isNotBlank();
    assertThat(s1.get("onBreak").asBoolean()).isFalse();

    clockIn(tok).andExpect(status().isConflict()); // already clocked in

    JsonNode s2 = body(clockOut(tok).andExpect(status().isOk()));
    assertThat(s2.get("open").asBoolean()).isFalse();

    clockOut(tok).andExpect(status().isConflict()); // not clocked in

    assertThat(auditCount("ATTENDANCE_CLOCK_IN")).isEqualTo(1);
    assertThat(auditCount("ATTENDANCE_CLOCK_OUT")).isEqualTo(1);
  }

  @Test
  void theDbPartialUniqueIndexRejectsASecondOpenSession() {
    insertOpenSession(emp.getId());
    assertThatThrownBy(() -> insertOpenSession(emp.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void totalsSumCompletedSessionsAndExcludeOpenOnes() throws Exception {
    insertCompletedSession(emp.getId(), 3, 2); // 1h completed
    insertOpenSession(emp.getId()); // in progress -> adds 0

    JsonNode me = body(myAttendance());
    assertThat(me.get("periodSeconds").asLong()).isEqualTo(3600);
    boolean hasOpen = false;
    for (JsonNode day : me.get("days")) {
      for (JsonNode s : day.get("sessions")) {
        if (s.get("clockOutAt").isNull()) {
          hasOpen = true;
          assertThat(s.get("workedSeconds").isNull()).isTrue();
        }
      }
    }
    assertThat(hasOpen).isTrue();
  }

  // --- Breaks ----------------------------------------------------------------

  @Test
  void breakFlow_oneOpenBreakAndClockOutGuard() throws Exception {
    String tok = empToken(emp);
    clockIn(tok).andExpect(status().isOk());

    JsonNode onBreak = body(breakStart(tok).andExpect(status().isOk()));
    assertThat(onBreak.get("onBreak").asBoolean()).isTrue();
    assertThat(onBreak.get("breakOpenSince").asText()).isNotBlank();

    breakStart(tok).andExpect(status().isConflict()); // already on a break
    clockOut(tok).andExpect(status().isConflict()); // must end the break first

    JsonNode ended = body(breakEnd(tok).andExpect(status().isOk()));
    assertThat(ended.get("onBreak").asBoolean()).isFalse();
    breakEnd(tok).andExpect(status().isConflict()); // no open break now

    clockOut(tok).andExpect(status().isOk()); // now allowed

    assertThat(auditCount("ATTENDANCE_BREAK_START")).isEqualTo(1);
    assertThat(auditCount("ATTENDANCE_BREAK_END")).isEqualTo(1);
  }

  @Test
  void workedTimeExcludesBreaks() throws Exception {
    String session = insertCompletedSession(emp.getId(), 3, 1); // 2h = 7200s
    insertCompletedBreak(session, 150, 120); // a 30-min break inside it = 1800s

    JsonNode me = body(myAttendance());
    assertThat(me.get("periodSeconds").asLong()).isEqualTo(7200 - 1800); // worked excludes the break
  }

  @Test
  void theDbPartialUniqueIndexRejectsASecondOpenBreak() {
    String session = insertOpenSession(emp.getId());
    insertOpenBreak(session);
    assertThatThrownBy(() -> insertOpenBreak(session))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- Access control --------------------------------------------------------

  @Test
  void onlyCredentialedEmployeesHaveAttendanceAndBreaks() throws Exception {
    String otp = empToken(uncredentialed);
    clockIn(otp).andExpect(status().isForbidden());
    breakStart(otp).andExpect(status().isForbidden());
    breakEnd(otp).andExpect(status().isForbidden());
    mvc.perform(get("/attendance/me/status").header("Authorization", "Bearer " + otp))
        .andExpect(status().isForbidden());
    clockIn(token(hr)).andExpect(status().isForbidden()); // a staff user is not an employee
  }

  // --- Manager team scope ----------------------------------------------------

  @Test
  void managerSeesOnlyHisTeamRosterAndDrilldownAndActivity() throws Exception {
    clockIn(empToken(emp));
    breakStart(empToken(emp)); // adds a BREAK_START activity event
    String mgr = token(manager);

    JsonNode roster = body(mvc.perform(get("/attendance/team/summary").header("Authorization", "Bearer " + mgr)).andExpect(status().isOk()));
    assertThat(roster)
        .anySatisfy(
            r -> {
              assertThat(r.get("employeeId").asText()).isEqualTo(emp.getId());
              assertThat(r.get("clockedIn").asBoolean()).isTrue();
              assertThat(r.get("onBreak").asBoolean()).isTrue();
            });
    assertThat(roster)
        .noneSatisfy(r -> assertThat(r.get("employeeId").asText()).isEqualTo(outOfScope.getId()));

    mvc.perform(get("/attendance/team?employeeId=" + emp.getId()).header("Authorization", "Bearer " + mgr))
        .andExpect(status().isOk());
    mvc.perform(get("/attendance/team?employeeId=" + outOfScope.getId()).header("Authorization", "Bearer " + mgr))
        .andExpect(status().isForbidden());

    JsonNode feed = body(mvc.perform(get("/attendance/team/activity").header("Authorization", "Bearer " + mgr)).andExpect(status().isOk()));
    // Newest first: BREAK_START then IN, both for the in-scope employee.
    assertThat(feed.get("totalElements").asInt()).isEqualTo(2);
    assertThat(feed.get("content").get(0).get("type").asText()).isEqualTo("BREAK_START");
    assertThat(feed.get("content").get(1).get("type").asText()).isEqualTo("IN");

    JsonNode otherRoster = body(mvc.perform(get("/attendance/team/summary").header("Authorization", "Bearer " + token(otherManager))).andExpect(status().isOk()));
    assertThat(otherRoster)
        .noneSatisfy(r -> assertThat(r.get("employeeId").asText()).isEqualTo(emp.getId()));
  }

  @Test
  void nonManagersCannotReachTheTeamEndpoints() throws Exception {
    mvc.perform(get("/attendance/team/summary").header("Authorization", "Bearer " + token(hr)))
        .andExpect(status().isForbidden());
    mvc.perform(get("/attendance/team/activity").header("Authorization", "Bearer " + empToken(emp)))
        .andExpect(status().isForbidden());
  }

  // --- helpers ---------------------------------------------------------------

  private ResultActions clockIn(String token) throws Exception {
    return mvc.perform(post("/attendance/clock-in").header("Authorization", "Bearer " + token));
  }

  private ResultActions clockOut(String token) throws Exception {
    return mvc.perform(post("/attendance/clock-out").header("Authorization", "Bearer " + token));
  }

  private ResultActions breakStart(String token) throws Exception {
    return mvc.perform(post("/attendance/break/start").header("Authorization", "Bearer " + token));
  }

  private ResultActions breakEnd(String token) throws Exception {
    return mvc.perform(post("/attendance/break/end").header("Authorization", "Bearer " + token));
  }

  private ResultActions myAttendance() throws Exception {
    return mvc.perform(get("/attendance/me").header("Authorization", "Bearer " + empToken(emp)))
        .andExpect(status().isOk());
  }

  private JsonNode body(ResultActions actions) throws Exception {
    return json.readTree(actions.andReturn().getResponse().getContentAsString());
  }

  private String insertOpenSession(String employeeId) {
    String id = "att_" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO \"attendance_sessions\"(\"id\",\"employeeId\",\"companyId\",\"clockInAt\",\"shiftDate\") "
            + "VALUES (?,?,?, now(), ((now() AT TIME ZONE 'Asia/Kolkata') - interval '4 hours')::date)",
        id, employeeId, acme);
    return id;
  }

  private String insertCompletedSession(String employeeId, int inHoursAgo, int outHoursAgo) {
    String id = "att_" + UUID.randomUUID();
    jdbc.update(
        "INSERT INTO \"attendance_sessions\"(\"id\",\"employeeId\",\"companyId\",\"clockInAt\",\"clockOutAt\",\"shiftDate\") "
            + "VALUES (?,?,?, now() - (? || ' hours')::interval, now() - (? || ' hours')::interval, "
            + "(((now() - (? || ' hours')::interval) AT TIME ZONE 'Asia/Kolkata') - interval '4 hours')::date)",
        id, employeeId, acme, inHoursAgo, outHoursAgo, inHoursAgo);
    return id;
  }

  private void insertOpenBreak(String sessionId) {
    jdbc.update(
        "INSERT INTO \"attendance_breaks\"(\"id\",\"sessionId\",\"companyId\",\"breakStartAt\") VALUES (?,?,?, now())",
        "brk_" + UUID.randomUUID(), sessionId, acme);
  }

  private void insertCompletedBreak(String sessionId, int startMinAgo, int endMinAgo) {
    jdbc.update(
        "INSERT INTO \"attendance_breaks\"(\"id\",\"sessionId\",\"companyId\",\"breakStartAt\",\"breakEndAt\") "
            + "VALUES (?,?,?, now() - (? || ' minutes')::interval, now() - (? || ' minutes')::interval)",
        "brk_" + UUID.randomUUID(), sessionId, acme, startMinAgo, endMinAgo);
  }

  private long auditCount(String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM \"audit_logs\" WHERE \"action\" = ?", Long.class, action);
  }

  private String company(String code, String mailDomain) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    c.setMailDomain(mailDomain);
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

  private void team(String companyId, String hrId, String managerId) {
    Team t = new Team();
    t.setName("Team " + managerId);
    t.setCompanyId(companyId);
    t.setHrUserId(hrId);
    t.setManagerUserId(managerId);
    teams.save(t);
  }

  private Employee employee(String companyId, User hrUser, String mailAddress) {
    Employee e = new Employee();
    e.setFullName("Emp " + (mailAddress == null ? "nomail" : mailAddress.split("@")[0]));
    e.setEmail("personal-" + UUID.randomUUID() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrUser.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    e.setEmployeeCode("ACME-EMP-" + (int) (Math.random() * 1000000));
    if (mailAddress != null) {
      e.setMailLocalPart(mailAddress.split("@")[0]);
      e.setMailAddress(mailAddress);
      e.setPasswordHash("hashed");
    }
    return employees.save(e);
  }

  private String empToken(Employee e) {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee(
            e.getId(), e.getEmployeeCode(), e.getEmail(), e.getCompanyId(), e.getFullName(), e.getMailAddress()));
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null));
  }
}
