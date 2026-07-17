package com.ihrms.accountant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The ACCOUNTANT role (ARCHITECTURE.md §2/§6): a SUPER_ADMIN-provisioned SINGLETON, cross-company,
 * READ-ONLY viewer of APPROVED employees. Storage is mocked so it runs in {@code ./mvnw package} with
 * only a DB. Covers: singleton provisioning; approved-only across companies (in-flight hidden); masked
 * record + audited reveal (HR's mechanism); approval-only audit; and 403 on every write.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AccountantApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired Form1PersonalRepository form1s;
  @Autowired Form2InfoRepository form2s;
  @Autowired AuditLogRepository auditLogs;
  @Autowired TeamRepository teams;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String superToken;
  private String accountantToken;
  private String companyA;
  private String companyB;
  private User hrA;
  private Employee approvedA; // has offeredCtc + panNumber (sensitive)
  private Employee approvedB; // in companyB / hrB — invisible to companyA's team accountant
  private Employee inflightA; // SUBMITTED — must be invisible to the accountant

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    companyB = company("BBB");
    hrA = user(companyA, UserRole.HR, "hra@a.test");
    User hrB = user(companyB, UserRole.HR, "hrb@b.test");

    approvedA = approved(companyA, hrA.getId(), "AAA-EMP-000001", "Anita Approved", "anita@p.test");
    approvedB = approved(companyB, hrB.getId(), "BBB-EMP-000001", "Bob Approved", "bob@p.test");
    inflightA = submitted(companyA, hrA.getId(), "Ivan Inflight", "ivan@p.test");

    // Approval + a non-approval audit event in each company (the latter must be excluded).
    auditRow(companyA, "APPROVAL_APPROVED", approvedA.getId());
    auditRow(companyB, "APPROVAL_ROUTED", null);
    auditRow(companyA, "FORM_REVIEWED", approvedA.getId()); // not an approval event

    User sa = user(null, UserRole.SUPER_ADMIN, "super@x.test");
    superToken = token(sa, UserRole.SUPER_ADMIN);

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://storage.local/get?sig=test");
  }

  // --- Provisioning (SUPER_ADMIN, singleton) --------------------------------

  @Test
  void superAdminProvisionsTheSingleAccountantAndASecondIsRejected() throws Exception {
    assertThat(provisioningStatus(superToken).get("exists").asBoolean()).isFalse();

    MvcResult created =
        mvc.perform(
                post("/provisioning/accounts-admin")
                    .header("Authorization", "Bearer " + superToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                        Map.of("name", "Casey Counts", "localPart", "Casey", "password", "Ledger@2026"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode body = json.readTree(created.getResponse().getContentAsString());
    // localPart@ihrms (the platform domain) IS the login email, lower-cased (§8).
    assertThat(body.get("accountant").get("email").asText()).isEqualTo("casey@ihrms");
    assertThat(body.get("devPassword").asText()).isEqualTo("Ledger@2026"); // echoed in dev
    assertThat(users.existsByRole(UserRole.ACCOUNTS_ADMIN)).isTrue();
    assertThat(provisioningStatus(superToken).get("exists").asBoolean()).isTrue();

    // Singleton: a second create is rejected.
    mvc.perform(
            post("/provisioning/accounts-admin")
                .header("Authorization", "Bearer " + superToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", "Second One", "localPart", "second", "password", "Another@2026"))))
        .andExpect(status().isConflict());
    assertThat(auditLogs.findByAction("ACCOUNTS_ADMIN_PROVISIONED")).hasSize(1);

    // The provisioned Accountant signs in with email + password (no OTP) and is routed by role.
    MvcResult login =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(
                        Map.of("email", "casey@ihrms", "password", "Ledger@2026"))))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(json.readTree(login.getResponse().getContentAsString()).get("session").get("role").asText())
        .isEqualTo("ACCOUNTS_ADMIN");
  }

  @Test
  void superAdminCanRemoveAndReplaceTheAccountsAdmin() throws Exception {
    provision(superToken, "Casey Counts", "casey", "Ledger@2026");
    assertThat(users.existsByRole(UserRole.ACCOUNTS_ADMIN)).isTrue();

    // Remove it -> singleton is gone (audited), so a replacement can be created.
    MvcResult removed =
        mvc.perform(delete("/provisioning/accounts-admin").header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(removed.getResponse().getContentAsString()).get("exists").asBoolean()).isFalse();
    assertThat(users.existsByRole(UserRole.ACCOUNTS_ADMIN)).isFalse();
    assertThat(auditLogs.findByAction("ACCOUNTS_ADMIN_REMOVED")).hasSize(1);

    // A brand-new Accounts Admin (even reusing the freed mailbox name) can now be provisioned.
    provision(superToken, "Dana Digits", "casey", "Ledger@2027");
    assertThat(users.existsByRole(UserRole.ACCOUNTS_ADMIN)).isTrue();

    // Only SUPER_ADMIN may remove.
    String hrToken = token(user(companyA, UserRole.HR, "hr9@a.test"), UserRole.HR);
    mvc.perform(delete("/provisioning/accounts-admin").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void onlySuperAdminMayProvisionOrCheckTheAccountant() throws Exception {
    String hrToken = token(user(companyA, UserRole.HR, "hr2@a.test"), UserRole.HR);
    mvc.perform(get("/provisioning/accounts-admin").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/provisioning/accounts-admin")
                .header("Authorization", "Bearer " + hrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", "X Y", "localPart", "x", "password", "Passw0rd!"))))
        .andExpect(status().isForbidden());
  }

  // --- Reads: approved-only across companies --------------------------------

  @Test
  void accountantSeesApprovedEmployeesAcrossAllCompaniesButNotInFlight() throws Exception {
    accountantToken = mintAccountant();

    JsonNode all = list(null);
    assertThat(all.get("totalElements").asInt()).isEqualTo(2); // both companies' approved
    assertThat(all.get("content")).allSatisfy(r -> assertThat(r.get("employeeCode").asText()).contains("-EMP-"));
    // The company name is resolved for the DataTable.
    assertThat(all.get("content")).anySatisfy(r -> assertThat(r.get("companyName").asText()).isEqualTo("AAA Inc"));

    // Company filter narrows to one company.
    assertThat(list("?companyId=" + companyA).get("totalElements").asInt()).isEqualTo(1);

    // The in-flight (SUBMITTED) employee is NOT visible — record read is 404.
    mvc.perform(get("/accountant/employees/" + inflightA.getId()).header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void accountantRecordIsMaskedAndRevealIsAudited() throws Exception {
    accountantToken = mintAccountant();

    MvcResult res =
        mvc.perform(get("/accountant/employees/" + approvedA.getId()).header("Authorization", "Bearer " + accountantToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode record = json.readTree(res.getResponse().getContentAsString());
    assertThat(record.get("form1").get("panNumber").asText()).isEqualTo("********"); // PAN surfaces under Form 1 (§3.2)
    assertThat(auditLogs.findByAction("EMPLOYEE_RECORD_VIEWED"))
        .singleElement()
        .satisfies(a -> assertThat(a.getCompanyId()).isEqualTo(companyA)); // logged under the company

    MvcResult revealed =
        mvc.perform(post("/accountant/employees/" + approvedA.getId() + "/reveal").header("Authorization", "Bearer " + accountantToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode plain = json.readTree(revealed.getResponse().getContentAsString());
    assertThat(plain.get("form1").get("panNumber").asText()).isEqualTo("ABCDE1234F");
    assertThat(auditLogs.findByAction("SENSITIVE_FIELD_REVEALED")).hasSize(1);
  }

  @Test
  void approvalAuditIsCrossCompanyAndApprovalOnly() throws Exception {
    accountantToken = mintAccountant();
    MvcResult res =
        mvc.perform(get("/accountant/audit").header("Authorization", "Bearer " + accountantToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode page = json.readTree(res.getResponse().getContentAsString());
    // Two approval events (company A APPROVAL_APPROVED + company B APPROVAL_ROUTED); FORM_REVIEWED excluded.
    assertThat(page.get("totalElements").asInt()).isEqualTo(2);
    assertThat(page.get("content")).allSatisfy(r -> assertThat(r.get("action").asText()).startsWith("APPROVAL_"));
    assertThat(page.get("content")).anySatisfy(r -> assertThat(r.get("companyId").asText()).isEqualTo(companyB));
  }

  // --- Team-scoped ACCOUNTANT (own team only) -------------------------------

  @Test
  void teamAccountantSeesOnlyItsOwnTeamsApprovedEmployees() throws Exception {
    // A team in company A whose HR is hrA and whose Accountant is a team-scoped ACCOUNTANT user.
    User teamAcc = user(companyA, UserRole.ACCOUNTANT, "team-acc@a.test");
    Team teamA = new Team();
    teamA.setName("Team A");
    teamA.setCompanyId(companyA);
    teamA.setHrUserId(hrA.getId());
    teamA.setAccountantUserId(teamAcc.getId());
    teams.save(teamA);
    String token =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                teamAcc.getId(), teamAcc.getEmail(), teamAcc.getName(),
                UserRole.ACCOUNTANT, companyA, teamA.getId()));

    // Sees only company A / hrA's approved employee — not company B's, not the in-flight one.
    JsonNode list =
        json.readTree(
            mvc.perform(get("/accountant/employees").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(list.get("totalElements").asInt()).isEqualTo(1);
    assertThat(list.get("content").get(0).get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");

    // Own team's approved -> 200; another team/company's -> 404; in-flight -> 404.
    mvc.perform(get("/accountant/employees/" + approvedA.getId()).header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    mvc.perform(get("/accountant/employees/" + approvedB.getId()).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    mvc.perform(get("/accountant/employees/" + inflightA.getId()).header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());

    // Approval audit is team-scoped: only company A's approval event targeting this team's employee.
    JsonNode audit =
        json.readTree(
            mvc.perform(get("/accountant/audit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(audit.get("totalElements").asInt()).isEqualTo(1);
    assertThat(audit.get("content").get(0).get("action").asText()).isEqualTo("APPROVAL_APPROVED");

    // Reveal works + is audited; writes are forbidden.
    mvc.perform(post("/accountant/employees/" + approvedA.getId() + "/reveal").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    assertThat(auditLogs.findByAction("SENSITIVE_FIELD_REVEALED")).hasSize(1);
    mvc.perform(
            post("/employees")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
  }

  // --- Team-wise browsing (§2) ----------------------------------------------

  @Test
  void teamWiseBrowsing_accountsAdminDrillsCompanyTeamEmployee_accountantOwnTeamOnly() throws Exception {
    // Company A: a full team (hrA + manager + accountant); its approved employee is approvedA.
    User mgrA = user(companyA, UserRole.MANAGER, "mgra@a.test");
    User accA = user(companyA, UserRole.ACCOUNTANT, "acca@a.test");
    Team teamA = new Team();
    teamA.setName("Alpha Team");
    teamA.setCompanyId(companyA);
    teamA.setHrUserId(hrA.getId());
    teamA.setManagerUserId(mgrA.getId());
    teamA.setAccountantUserId(accA.getId());
    teams.save(teamA);
    // Company B: a team keyed to approvedB's onboarding HR (proves cross-company drilldown).
    Team teamB = new Team();
    teamB.setName("Beta Team");
    teamB.setCompanyId(companyB);
    teamB.setHrUserId(approvedB.getOnboardingHrId());
    teams.save(teamB);

    String adminToken = mintAccountant(); // the cross-company ACCOUNTS_ADMIN

    // 1) Companies with team + approved counts (both companies).
    JsonNode companiesJson = getJson("/accountant/companies", adminToken);
    assertThat(companiesJson).hasSize(2);
    JsonNode rowA = firstWhere(companiesJson, "id", companyA);
    assertThat(rowA.get("teamCount").asInt()).isEqualTo(1);
    assertThat(rowA.get("employeeCount").asInt()).isEqualTo(1); // approvedA (in-flight excluded)

    // 2) Company A's teams — HR/Manager names + approved count.
    JsonNode teamsA = getJson("/accountant/companies/" + companyA + "/teams", adminToken);
    assertThat(teamsA).hasSize(1);
    assertThat(teamsA.get(0).get("name").asText()).isEqualTo("Alpha Team");
    assertThat(teamsA.get(0).get("hrName").asText()).isEqualTo(hrA.getName());
    assertThat(teamsA.get(0).get("managerName").asText()).isEqualTo(mgrA.getName());
    assertThat(teamsA.get(0).get("employeeCount").asInt()).isEqualTo(1);

    // 3) A team's approved employees — teamA -> approvedA only; teamB (company B) -> approvedB.
    JsonNode empA = getJson("/accountant/teams/" + teamA.getId() + "/employees", adminToken);
    assertThat(empA.get("totalElements").asInt()).isEqualTo(1);
    assertThat(empA.get("content").get(0).get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    JsonNode empB = getJson("/accountant/teams/" + teamB.getId() + "/employees", adminToken);
    assertThat(empB.get("content").get(0).get("employeeCode").asText()).isEqualTo("BBB-EMP-000001");

    // --- ACCOUNTANT: own team only, no company/team picking ---
    String accToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                accA.getId(), accA.getEmail(), accA.getName(), UserRole.ACCOUNTANT, companyA, teamA.getId()));

    JsonNode myTeam = getJson("/accountant/my-team", accToken);
    assertThat(myTeam.get("teamId").asText()).isEqualTo(teamA.getId());
    assertThat(myTeam.get("teamName").asText()).isEqualTo("Alpha Team");
    assertThat(myTeam.get("companyName").asText()).isEqualTo("AAA Inc");

    // Own team roster -> ok (1 approved); ANOTHER team -> 404 (never widen).
    assertThat(getJson("/accountant/teams/" + teamA.getId() + "/employees", accToken)
            .get("totalElements").asInt())
        .isEqualTo(1);
    mvc.perform(get("/accountant/teams/" + teamB.getId() + "/employees")
            .header("Authorization", "Bearer " + accToken))
        .andExpect(status().isNotFound());
    // Cross-company browsing is Accounts-Admin only.
    mvc.perform(get("/accountant/companies").header("Authorization", "Bearer " + accToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/accountant/companies/" + companyA + "/teams")
            .header("Authorization", "Bearer " + accToken))
        .andExpect(status().isForbidden());
  }

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

  // --- No writes ------------------------------------------------------------

  @Test
  void accountantCannotPerformAnyWrite() throws Exception {
    accountantToken = mintAccountant();
    // Onboard (HR), verify a form (HR), create a company (SUPER_ADMIN), provision an accountant (SUPER_ADMIN).
    forbidden(post("/employees").content("{}"));
    forbidden(patch("/employees/" + approvedA.getId() + "/forms/FORM1").content("{\"decision\":\"VERIFIED\"}"));
    forbidden(post("/companies").content("{}"));
    forbidden(post("/provisioning/accounts-admin").content("{}"));
  }

  // --- helpers --------------------------------------------------------------

  private void forbidden(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
      throws Exception {
    mvc.perform(
            req.header("Authorization", "Bearer " + accountantToken).contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isForbidden());
  }

  private void provision(String token, String name, String localPart, String password) throws Exception {
    mvc.perform(
            post("/provisioning/accounts-admin")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(
                    Map.of("name", name, "localPart", localPart, "password", password))))
        .andExpect(status().isCreated());
  }

  private JsonNode provisioningStatus(String token) throws Exception {
    return json.readTree(
        mvc.perform(get("/provisioning/accounts-admin").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode list(String query) throws Exception {
    return json.readTree(
        mvc.perform(get("/accountant/employees" + (query == null ? "" : query))
                .header("Authorization", "Bearer " + accountantToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String mintAccountant() {
    User acc = user(null, UserRole.ACCOUNTS_ADMIN, "casey@books.test");
    return token(acc, UserRole.ACCOUNTS_ADMIN);
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
    return users.save(u);
  }

  private Employee approved(String companyId, String hrId, String code, String name, String email) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName(name);
    e.setEmail(email);
    e.setDesignation("Software Engineer");
    e.setDateOfJoining(LocalDate.parse("2026-07-01"));
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    employees.save(e);

    Form1Personal f1 = new Form1Personal();
    f1.setEmployeeId(e.getId());
    f1.setData(Map.of("name", name));
    f1.setOfferedCtc("2500000"); // sensitive
    f1.setStatus(SectionStatus.VERIFIED);
    form1s.save(f1);

    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(e.getId());
    f2.setData(Map.of("fullName", name));
    f2.setPanNumber("ABCDE1234F"); // sensitive
    f2.setStatus(SectionStatus.VERIFIED);
    form2s.save(f2);
    return e;
  }

  private Employee submitted(String companyId, String hrId, String name, String email) {
    Employee e = new Employee();
    e.setFullName(name);
    e.setEmail(email);
    e.setDesignation("Analyst");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.SUBMITTED); // no code — in-flight
    return employees.save(e);
  }

  private void auditRow(String companyId, String action, String targetId) {
    AuditLog l = new AuditLog();
    l.setCompanyId(companyId);
    l.setActorType("USER");
    l.setActorId("someone");
    l.setAction(action);
    l.setTargetType("Employee");
    l.setTargetId(targetId);
    l.setMetadata(Map.of());
    auditLogs.save(l);
  }

  private String token(User u, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), role, u.getCompanyId(), null));
  }
}
