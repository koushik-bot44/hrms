package com.ihrms.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
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

/** Company-Admin team management (contract §3.4): company scoping, one-HR-one-Manager, delete rules. */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class TeamsApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String companyA;
  private String companyB;
  private String adminA;
  private String adminB;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    companyB = company("BBB");
    adminA = adminToken("admin-a", companyA);
    adminB = adminToken("admin-b", companyB);
  }

  @Test
  void createsTeamsScopedToOwnCompany() throws Exception {
    String teamId = createTeam(adminA, "Engineering");

    MvcResult detail =
        mvc.perform(get("/teams/" + teamId).header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(detail.getResponse().getContentAsString());
    assertThat(body.get("hr").isNull()).isTrue();
    assertThat(body.get("manager").isNull()).isTrue();
    assertThat(body.get("memberCount").asInt()).isZero();
    assertThat(body.get("members").isArray()).isTrue();
    assertThat(body.get("members")).isEmpty();

    // Company B cannot see or fetch company A's team.
    MvcResult listB =
        mvc.perform(get("/teams").header("Authorization", "Bearer " + adminB))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(listB.getResponse().getContentAsString())).isEmpty();
    mvc.perform(get("/teams/" + teamId).header("Authorization", "Bearer " + adminB))
        .andExpect(status().isNotFound());

    assertThat(auditLogs.findByAction("TEAM_CREATED"))
        .singleElement()
        .satisfies(row -> assertThat(row.getCompanyId()).isEqualTo(companyA));
  }

  @Test
  void assignsNewHrAndManagerWithDevPassword() throws Exception {
    String teamId = createTeam(adminA, "Engineering");

    MvcResult hrRes =
        mvc.perform(asAdminA(put("/teams/" + teamId + "/hr"),
                Map.of("name", "Holly HR", "email", "holly@a.test", "password", "HollyHR@1")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode hrBody = json.readTree(hrRes.getResponse().getContentAsString());
    assertThat(hrBody.get("team").get("hr").get("email").asText()).isEqualTo("holly@a.test");
    assertThat(hrBody.get("team").get("hr").get("role").asText()).isEqualTo("HR");
    // The initial password is echoed in dev (it is what the admin set).
    assertThat(hrBody.get("devPassword").asText()).isEqualTo("HollyHR@1");

    MvcResult mgrRes =
        mvc.perform(asAdminA(put("/teams/" + teamId + "/manager"),
                Map.of("name", "Max Manager", "email", "max@a.test", "password", "MaxMgr@1")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode mgrBody = json.readTree(mgrRes.getResponse().getContentAsString());
    assertThat(mgrBody.get("team").get("manager").get("email").asText()).isEqualTo("max@a.test");
    assertThat(mgrBody.get("team").get("memberCount").asInt()).isEqualTo(2);
  }

  @Test
  void assigningAnotherHrReplacesAndDetachesTheFirst() throws Exception {
    String teamId = createTeam(adminA, "Engineering");
    assignNewHr(teamId, "first@a.test");
    MvcResult second = assignNewHr(teamId, "second@a.test");

    JsonNode team = json.readTree(second.getResponse().getContentAsString()).get("team");
    assertThat(team.get("hr").get("email").asText()).isEqualTo("second@a.test");
    assertThat(team.get("memberCount").asInt()).isEqualTo(1); // first HR detached

    // The replaced HR is unassigned again -> shows up as assignable.
    MvcResult assignable =
        mvc.perform(get("/teams/assignable-users?role=HR").header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(assignable.getResponse().getContentAsString()).contains("first@a.test");
  }

  @Test
  void assignExistingRejectsWrongRoleAndCrossCompany() throws Exception {
    String teamId = createTeam(adminA, "Engineering");

    // Existing HR in company A -> assigned successfully.
    User hr = newUser(companyA, UserRole.HR, "hr-a@a.test");
    mvc.perform(asAdminA(put("/teams/" + teamId + "/hr"), Map.of("userId", hr.getId())))
        .andExpect(status().isOk());

    // A MANAGER user cannot fill the HR slot.
    User manager = newUser(companyA, UserRole.MANAGER, "mgr-a@a.test");
    mvc.perform(asAdminA(put("/teams/" + teamId + "/hr"), Map.of("userId", manager.getId())))
        .andExpect(status().isBadRequest());

    // A user from another company is not found in this company's scope.
    User foreign = newUser(companyB, UserRole.HR, "hr-b@b.test");
    mvc.perform(asAdminA(put("/teams/" + teamId + "/hr"), Map.of("userId", foreign.getId())))
        .andExpect(status().isNotFound());
  }

  @Test
  void assignableUsersListsOnlyUnassignedOfRole() throws Exception {
    String teamId = createTeam(adminA, "Engineering");
    newUser(companyA, UserRole.HR, "free1@a.test");
    User free2 = newUser(companyA, UserRole.HR, "free2@a.test");

    MvcResult before =
        mvc.perform(get("/teams/assignable-users?role=HR").header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(before.getResponse().getContentAsString())).hasSize(2);

    mvc.perform(asAdminA(put("/teams/" + teamId + "/hr"), Map.of("userId", free2.getId())))
        .andExpect(status().isOk());

    MvcResult after =
        mvc.perform(get("/teams/assignable-users?role=HR").header("Authorization", "Bearer " + adminA))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(after.getResponse().getContentAsString())).hasSize(1);
  }

  @Test
  void deletesTeamUnlessItHasApprovalHistory() throws Exception {
    String plain = createTeam(adminA, "Disposable");
    mvc.perform(delete("/teams/" + plain).header("Authorization", "Bearer " + adminA))
        .andExpect(status().isOk());
    mvc.perform(get("/teams/" + plain).header("Authorization", "Bearer " + adminA))
        .andExpect(status().isNotFound());

    // A team referenced by an approval request cannot be deleted.
    String withHistory = createTeam(adminA, "Has History");
    seedApproval(withHistory);
    mvc.perform(delete("/teams/" + withHistory).header("Authorization", "Bearer " + adminA))
        .andExpect(status().isConflict());
  }

  @Test
  void superAdminCannotAccessTeams() throws Exception {
    String superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User("s-1", "s@x.test", "S", UserRole.SUPER_ADMIN, null, null));
    mvc.perform(get("/teams").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isForbidden());
  }

  // --- fixtures -------------------------------------------------------------

  private MvcResult assignNewHr(String teamId, String email) throws Exception {
    return mvc.perform(asAdminA(put("/teams/" + teamId + "/hr"),
            Map.of("name", "HR " + email, "email", email, "password", "NewStaff@1")))
        .andExpect(status().isOk())
        .andReturn();
  }

  private void seedApproval(String teamId) {
    User hr = newUser(companyA, UserRole.HR, "appr-hr@a.test");
    User manager = newUser(companyA, UserRole.MANAGER, "appr-mgr@a.test");
    Employee employee = new Employee();
    employee.setEmployeeCode("AAA-EMP-000001");
    employee.setEmail("appr-emp@a.test");
    employee.setCompanyId(companyA);
    employee.setOnboardingHrId(hr.getId());
    employees.save(employee);

    ApprovalRequest approval = new ApprovalRequest();
    approval.setEmployeeId(employee.getId());
    approval.setHrUserId(hr.getId());
    approval.setManagerUserId(manager.getId());
    approval.setTeamId(teamId);
    approvals.save(approval);
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User newUser(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private String adminToken(String userId, String companyId) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(userId, userId + "@x.test", "Admin", UserRole.COMPANY_ADMIN, companyId, null));
  }

  private String createTeam(String token, String name) throws Exception {
    MvcResult res =
        mvc.perform(
                post("/teams")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("name", name))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString()).get("id").asText();
  }

  private MockHttpServletRequestBuilder asAdminA(MockHttpServletRequestBuilder builder, Object body)
      throws Exception {
    return builder
        .header("Authorization", "Bearer " + adminA)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }
}
