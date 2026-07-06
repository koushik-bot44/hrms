package com.ihrms.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Role dashboard summaries (§2/§9): each role's counts are SCOPED exactly like its list views and
 * match the underlying data — an HR sees only their own onboarded employees, a Company Admin only
 * their company, Super Admin org-wide. Proves cross-HR and cross-company isolation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class DashboardApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired JdbcTemplate jdbc;

  private String companyA;
  private String companyB;
  private User hr1;
  private User hr2;
  private User mgr1;
  private User caA;
  private User caB;
  private User superAdmin;
  private Employee e3; // company A / hr1 / SUBMITTED

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    superAdmin = user(null, UserRole.SUPER_ADMIN, "super@root.test");
    companyA = company("AAA");
    companyB = company("BBB");
    caA = user(companyA, UserRole.COMPANY_ADMIN, "ca-a@acme.test");
    caB = user(companyB, UserRole.COMPANY_ADMIN, "ca-b@beta.test");
    hr1 = user(companyA, UserRole.HR, "hr1@acme.test");
    hr2 = user(companyA, UserRole.HR, "hr2@acme.test");
    mgr1 = user(companyA, UserRole.MANAGER, "mgr1@acme.test");
    team(companyA, hr1.getId(), mgr1.getId());

    // Company A / hr1: INVITED, IN_PROGRESS, SUBMITTED, APPROVED, REJECTED
    emp(companyA, hr1.getId(), EmployeeStatus.INVITED);
    emp(companyA, hr1.getId(), EmployeeStatus.IN_PROGRESS);
    e3 = emp(companyA, hr1.getId(), EmployeeStatus.SUBMITTED);
    emp(companyA, hr1.getId(), EmployeeStatus.APPROVED);
    emp(companyA, hr1.getId(), EmployeeStatus.REJECTED);
    // Company A / hr2: SUBMITTED, APPROVED
    emp(companyA, hr2.getId(), EmployeeStatus.SUBMITTED);
    emp(companyA, hr2.getId(), EmployeeStatus.APPROVED);
    // Company B / its own HR: SUBMITTED, INVITED
    User hrB = user(companyB, UserRole.HR, "hrb@beta.test");
    emp(companyB, hrB.getId(), EmployeeStatus.SUBMITTED);
    emp(companyB, hrB.getId(), EmployeeStatus.INVITED);

    // One pending approval routed to mgr1.
    ApprovalRequest a = new ApprovalRequest();
    a.setEmployeeId(e3.getId());
    a.setHrUserId(hr1.getId());
    a.setManagerUserId(mgr1.getId());
    a.setTeamId(teams.findByManagerUserId(mgr1.getId()).get(0).getId());
    a.setStatus(ApprovalStatus.PENDING);
    approvals.save(a);
  }

  @Test
  void hrSeesOnlyTheirOwnOnboardedEmployees() throws Exception {
    JsonNode hr1Stats = summary(tokenFor(hr1));
    assertThat(stat(hr1Stats, "hr.onboarded")).isEqualTo(5);
    assertThat(stat(hr1Stats, "hr.inProgress")).isEqualTo(2); // INVITED + IN_PROGRESS
    assertThat(stat(hr1Stats, "hr.pendingVerification")).isEqualTo(1);
    assertThat(stat(hr1Stats, "hr.approved")).isEqualTo(1);
    assertThat(stat(hr1Stats, "hr.rejected")).isEqualTo(1);

    // Cross-HR isolation: hr2's counts exclude hr1's employees.
    JsonNode hr2Stats = summary(tokenFor(hr2));
    assertThat(stat(hr2Stats, "hr.onboarded")).isEqualTo(2);
    assertThat(stat(hr2Stats, "hr.pendingVerification")).isEqualTo(1);

    // Activity is scoped to hr1's employees only.
    assertThat(hr1Stats.get("recentActivity")).isNotEmpty();
  }

  @Test
  void companyAdminSeesOnlyTheirCompany() throws Exception {
    JsonNode a = summary(tokenFor(caA));
    assertThat(stat(a, "ca.employees")).isEqualTo(7); // hr1(5) + hr2(2)
    assertThat(stat(a, "ca.teams")).isEqualTo(1);
    assertThat(stat(a, "ca.pendingVerification")).isEqualTo(2); // e3 + f1
    assertThat(stat(a, "ca.approved")).isEqualTo(2);

    // Cross-company isolation: company B's admin sees only its 2 employees.
    JsonNode b = summary(tokenFor(caB));
    assertThat(stat(b, "ca.employees")).isEqualTo(2);
  }

  @Test
  void superAdminSeesOrgWideTotals() throws Exception {
    JsonNode s = summary(tokenFor(superAdmin));
    assertThat(stat(s, "super.companiesActive")).isEqualTo(2);
    assertThat(stat(s, "super.companiesArchived")).isEqualTo(0);
    assertThat(stat(s, "super.employeesTotal")).isEqualTo(9); // 7 + 2
    assertThat(stat(s, "super.pendingApprovals")).isEqualTo(1);
    // Portfolio activity is flagged with each item's company.
    assertThat(s.get("recentActivity").get(0).has("company")).isTrue();
  }

  @Test
  void managerSeesOnlyTheirTeamScope() throws Exception {
    JsonNode m = summary(tokenFor(mgr1));
    assertThat(stat(m, "manager.pendingApprovals")).isEqualTo(1);
    assertThat(stat(m, "manager.teamEmployees")).isEqualTo(5); // hr1's employees
    assertThat(stat(m, "manager.approved")).isEqualTo(0);
  }

  @Test
  void employeeSeesTheirOwnProgress() throws Exception {
    String token =
        tokens.issueAccess(new IhrmsPrincipal.Employee(e3.getId(), null, e3.getEmail(), companyA));
    JsonNode s = summary(token);
    assertThat(s.get("role").asText()).isEqualTo("EMPLOYEE");
    JsonNode progress = s.get("employeeProgress");
    assertThat(progress.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(progress.get("formsTotal").asInt()).isEqualTo(4);
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode summary(String token) throws Exception {
    MvcResult res =
        mvc.perform(get("/dashboard/summary").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private static long stat(JsonNode body, String key) {
    for (JsonNode s : body.get("stats")) {
      if (key.equals(s.get("key").asText())) {
        return s.get("value").asLong();
      }
    }
    throw new AssertionError("stat not found: " + key);
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
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
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrId);
    t.setManagerUserId(managerId);
    teams.save(t);
  }

  private Employee emp(String companyId, String hrId, EmployeeStatus status) {
    Employee e = new Employee();
    e.setFullName("Emp " + status.name() + " " + email());
    e.setEmail("emp-" + email() + "@personal.test");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(status);
    return employees.save(e);
  }

  private final List<String> issued = new ArrayList<>();

  private String email() {
    issued.add("x");
    return String.valueOf(issued.size());
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), u.getTeamId()));
  }
}
