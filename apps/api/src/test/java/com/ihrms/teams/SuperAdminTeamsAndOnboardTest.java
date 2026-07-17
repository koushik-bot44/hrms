package com.ihrms.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * SUPER_ADMIN manages teams in ANY company and onboards into any company by selecting a team (§2).
 * Reuses the same TeamsService/onboarding as COMPANY_ADMIN/HR; only the companyId source + role gate
 * differ. COMPANY_ADMIN stays locked to its own company.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class SuperAdminTeamsAndOnboardTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String superToken;
  private String caToken; // COMPANY_ADMIN of company A
  private String companyA;
  private String companyB;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    User su = staff("Super Admin", "super@root.test", UserRole.SUPER_ADMIN, null);
    superToken = token(su);
    companyA = company("Acme Inc", "ACME");
    companyB = company("Beta LLC", "BETA");
    User ca = staff("Cara Admin", "ca@acme.test", UserRole.COMPANY_ADMIN, companyA);
    caToken = token(ca);
  }

  @Test
  void superAdminCreatesRenamesAssignsAndOnboardsIntoAnyCompany() throws Exception {
    // Create a team in company A.
    String teamId =
        idOf(post("/companies/" + companyA + "/teams"), superToken, Map.of("name", "Eng"), 201);

    // Assign HR (new) + Manager (new) — one HR + one Manager.
    JsonNode hrRes =
        json.readTree(
            perform(put("/companies/" + companyA + "/teams/" + teamId + "/hr"), superToken,
                    Map.of("name", "Hana HR", "localPart", "hana", "password", "HanaHR@12"), 200)
                .getResponse().getContentAsString());
    String hrId = hrRes.get("team").get("hr").get("id").asText();
    perform(put("/companies/" + companyA + "/teams/" + teamId + "/manager"), superToken,
        Map.of("name", "Max Mgr", "localPart", "max", "password", "MaxMgr@12"), 200);

    // Rename.
    perform(patch("/companies/" + companyA + "/teams/" + teamId), superToken,
        Map.of("name", "Engineering"), 200);

    // List reflects it (name + both slots filled).
    JsonNode listed =
        json.readTree(perform(get("/companies/" + companyA + "/teams"), superToken, null, 200)
            .getResponse().getContentAsString());
    assertThat(listed).anySatisfy(
        t -> {
          assertThat(t.get("name").asText()).isEqualTo("Engineering");
          assertThat(t.get("hr").get("email").asText()).isEqualTo("hana@acme");
          assertThat(t.get("manager").get("email").asText()).isEqualTo("max@acme");
        });

    // Onboard an employee into company A by FILLING FORM 2 + selecting the team -> attaches to that HR.
    JsonNode onboarded =
        json.readTree(
            perform(post("/companies/" + companyA + "/employees"), superToken,
                    saOnboard(teamId, "Eve Employee", "eve@personal.test"), 201)
                .getResponse().getContentAsString());
    assertThat(onboarded.get("loginUrl").asText()).contains("/employee/login");
    String employeeId = onboarded.get("employee").get("id").asText();

    Employee eve = employees.findById(employeeId).orElseThrow();
    assertThat(eve.getOnboardingHrId()).isEqualTo(hrId); // that team's HR, exactly as if HR onboarded
    assertThat(eve.getCompanyId()).isEqualTo(companyA);
    assertThat(eve.getStatus()).isEqualTo(EmployeeStatus.INVITED);
    assertThat(eve.getEmployeeCode()).isNull(); // still minted only at approval

    // Actions audited under the TARGET company.
    assertThat(auditLogs.findByAction("TEAM_CREATED"))
        .anySatisfy(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));
    assertThat(auditLogs.findByAction("EMPLOYEE_ONBOARDED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));

    // SA parity (§3.2): the Super Admin can edit Form 2 while the employee is INVITED — and a
    // personal-email change re-invites (identical to HR).
    JsonNode edited =
        json.readTree(
            perform(patch("/employees/" + employeeId + "/form2"), superToken,
                    Map.of("fullName", "Eve Employee", "personalEmail", "eve2@personal.test",
                        "designation", "Engineer", "dateOfJoining", "2026-08-01"), 200)
                .getResponse().getContentAsString());
    assertThat(edited.get("personalEmail").asText()).isEqualTo("eve2@personal.test");
    assertThat(employees.findById(employeeId).orElseThrow().getEmail()).isEqualTo("eve2@personal.test");
    assertThat(auditLogs.findByAction("EMPLOYEE_REINVITED"))
        .anySatisfy(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));
  }

  @Test
  void rejectsATeamThatIsNotInTheSelectedCompany() throws Exception {
    String teamInB = idOf(post("/companies/" + companyB + "/teams"), superToken, Map.of("name", "Team B"), 201);
    perform(put("/companies/" + companyB + "/teams/" + teamInB + "/hr"), superToken,
        Map.of("name", "HR B", "localPart", "hrb", "password", "HrBeta@123"), 200);

    // Onboard into A but pass B's team -> 400.
    perform(post("/companies/" + companyA + "/employees"), superToken,
        saOnboard(teamInB, "X Ray", "x@personal.test"), 400);
  }

  @Test
  void companyAdminCannotUseSuperAdminCrossCompanyPaths() throws Exception {
    // The /companies/** paths are SUPER_ADMIN-only.
    perform(get("/companies/" + companyA + "/teams"), caToken, null, 403);
    perform(post("/companies/" + companyA + "/employees"), caToken,
        saOnboard("x", "Y Zed", "y@personal.test"), 403);
  }

  // --- helpers --------------------------------------------------------------

  /** SA onboard body: team + the HR/SA-authored Form 2 (§3.2). */
  private static Map<String, Object> saOnboard(String teamId, String fullName, String email) {
    return Map.of(
        "teamId", teamId,
        "form2",
            Map.of(
                "fullName", fullName,
                "personalEmail", email,
                "designation", "Engineer",
                "dateOfJoining", "2026-08-01"));
  }

  private MvcResult perform(MockHttpServletRequestBuilder req, String token, Object body, int expected)
      throws Exception {
    req.header("Authorization", "Bearer " + token);
    if (body != null) {
      req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }
    return mvc.perform(req).andExpect(status().is(expected)).andReturn();
  }

  private String idOf(MockHttpServletRequestBuilder req, String token, Object body, int expected)
      throws Exception {
    return json.readTree(perform(req, token, body, expected).getResponse().getContentAsString())
        .get("id")
        .asText();
  }

  private String company(String name, String code) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User staff(String name, String email, UserRole role, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), u.getTeamId()));
  }
}
