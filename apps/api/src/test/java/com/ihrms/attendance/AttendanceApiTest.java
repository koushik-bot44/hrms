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
 * Attendance (§8a): clock in/out (one open session, 409s), status/totals, the DB partial-unique guard,
 * credentialed-only access, and the Manager's team-scoped roster / drill-down / activity feed.
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
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"attendance_sessions\",\"audit_logs\""
            + " RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");
    hr = user(acme, UserRole.HR, "hr@acme");
    manager = user(acme, UserRole.MANAGER, "mgr@acme");
    User hr2 = user(acme, UserRole.HR, "hr2@acme");
    otherManager = user(acme, UserRole.MANAGER, "mgr2@acme");
    team(acme, hr.getId(), manager.getId()); // manager's team: hr + manager
    team(acme, hr2.getId(), otherManager.getId()); // a different team

    emp = employee(acme, hr, "arjun@acme"); // credentialed
    uncredentialed = employee(acme, hr, null); // no mailbox
    outOfScope = employee(acme, hr2, "meera@acme"); // another team
  }

  // --- Clock in / out + status ----------------------------------------------

  @Test
  void clockInThenOutWithTheOneOpenSessionRules() throws Exception {
    String tok = empToken(emp);

    JsonNode s1 = json.readTree(clockIn(tok).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(s1.get("open").asBoolean()).isTrue();
    assertThat(s1.get("openSince").asText()).isNotBlank();

    // Already clocked in -> 409.
    clockIn(tok).andExpect(status().isConflict());

    JsonNode s2 = json.readTree(clockOut(tok).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    assertThat(s2.get("open").asBoolean()).isFalse();

    // Not clocked in -> 409.
    clockOut(tok).andExpect(status().isConflict());

    // Two events audited.
    assertThat(auditCount("ATTENDANCE_CLOCK_IN")).isEqualTo(1);
    assertThat(auditCount("ATTENDANCE_CLOCK_OUT")).isEqualTo(1);
  }

  @Test
  void theDbPartialUniqueIndexRejectsASecondOpenSession() {
    // First open session (raw insert) is fine...
    insertOpenSession(emp.getId());
    // ...a second OPEN session for the same employee violates the partial-unique index.
    assertThatThrownBy(() -> insertOpenSession(emp.getId()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void totalsSumCompletedSessionsAndExcludeOpenOnes() throws Exception {
    // A completed 1-hour session earlier today (IST) + one still open.
    insertCompletedSession(emp.getId(), 3, 2); // clocked in 3h ago, out 2h ago -> 3600s
    insertOpenSession(emp.getId()); // in progress -> adds 0

    JsonNode me =
        json.readTree(
            mvc.perform(get("/attendance/me").header("Authorization", "Bearer " + empToken(emp)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(me.get("periodSeconds").asLong()).isEqualTo(3600); // only the completed one
    // The open session appears with a null duration ("In progress").
    boolean hasOpen = false;
    for (JsonNode day : me.get("days")) {
      for (JsonNode s : day.get("sessions")) {
        if (s.get("clockOutAt").isNull()) {
          hasOpen = true;
          assertThat(s.get("durationSeconds").isNull()).isTrue();
        }
      }
    }
    assertThat(hasOpen).isTrue();
  }

  // --- Access control --------------------------------------------------------

  @Test
  void onlyCredentialedEmployeesHaveAttendance() throws Exception {
    // Uncredentialed employee -> 403 on every attendance endpoint.
    clockIn(empToken(uncredentialed)).andExpect(status().isForbidden());
    mvc.perform(get("/attendance/me/status").header("Authorization", "Bearer " + empToken(uncredentialed)))
        .andExpect(status().isForbidden());
    // A staff user is not an employee -> 403.
    clockIn(token(hr)).andExpect(status().isForbidden());
  }

  // --- Manager team scope ----------------------------------------------------

  @Test
  void managerSeesOnlyHisTeamRosterAndDrilldownAndActivity() throws Exception {
    clockIn(empToken(emp)); // an event + open session for the in-scope employee
    String mgr = token(manager);

    JsonNode roster =
        json.readTree(
            mvc.perform(get("/attendance/team/summary").header("Authorization", "Bearer " + mgr))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    // The in-scope credentialed employee is listed + clocked in; the other team's employee is NOT.
    assertThat(roster).anySatisfy(r -> {
      assertThat(r.get("employeeId").asText()).isEqualTo(emp.getId());
      assertThat(r.get("clockedIn").asBoolean()).isTrue();
    });
    assertThat(roster).noneSatisfy(
        r -> assertThat(r.get("employeeId").asText()).isEqualTo(outOfScope.getId()));

    // Drill-down: in-scope -> 200; another team's employee -> 403.
    mvc.perform(get("/attendance/team?employeeId=" + emp.getId()).header("Authorization", "Bearer " + mgr))
        .andExpect(status().isOk());
    mvc.perform(get("/attendance/team?employeeId=" + outOfScope.getId()).header("Authorization", "Bearer " + mgr))
        .andExpect(status().isForbidden());

    // Activity feed: only his team's events.
    JsonNode feed =
        json.readTree(
            mvc.perform(get("/attendance/team/activity").header("Authorization", "Bearer " + mgr))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(feed.get("totalElements").asInt()).isEqualTo(1);
    assertThat(feed.get("content").get(0).get("type").asText()).isEqualTo("IN");
    assertThat(feed.get("content").get(0).get("employeeId").asText()).isEqualTo(emp.getId());

    // Another manager (different team) does NOT see this employee.
    JsonNode otherRoster =
        json.readTree(
            mvc.perform(get("/attendance/team/summary").header("Authorization", "Bearer " + token(otherManager)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(otherRoster).noneSatisfy(
        r -> assertThat(r.get("employeeId").asText()).isEqualTo(emp.getId()));
  }

  @Test
  void nonManagersCannotReachTheTeamEndpoints() throws Exception {
    // The /attendance/team/** URL rule is MANAGER-only.
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

  private void insertOpenSession(String employeeId) {
    jdbc.update(
        "INSERT INTO \"attendance_sessions\"(\"id\",\"employeeId\",\"companyId\",\"clockInAt\") "
            + "VALUES (?,?,?, now())",
        "att_" + java.util.UUID.randomUUID(), employeeId, acme);
  }

  private void insertCompletedSession(String employeeId, int inHoursAgo, int outHoursAgo) {
    jdbc.update(
        "INSERT INTO \"attendance_sessions\"(\"id\",\"employeeId\",\"companyId\",\"clockInAt\",\"clockOutAt\") "
            + "VALUES (?,?,?, now() - (? || ' hours')::interval, now() - (? || ' hours')::interval)",
        "att_" + java.util.UUID.randomUUID(), employeeId, acme, inHoursAgo, outHoursAgo);
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
    e.setFullName("Emp " + (mailAddress == null ? "nomail" : mailAddress));
    e.setEmail("personal-" + java.util.UUID.randomUUID() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrUser.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    e.setEmployeeCode("ACME-EMP-" + (int) (Math.random() * 100000));
    if (mailAddress != null) {
      e.setMailLocalPart(mailAddress.substring(0, mailAddress.indexOf('@')));
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
