package com.ihrms.review;

import static org.assertj.core.api.Assertions.assertThat;
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

/**
 * HR assigns an APPROVED employee internal credentials (§8, Stage 5): a mailbox address + password
 * (typed OR generated), emailed to their personal address; the employee can then sign in at {@code
 * /login}. Allowed for the onboarding HR (own onboarded) OR a COMPANY_ADMIN of the same company
 * (company-wide, §6); every other role is denied. Audited.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class EmployeeCredentialsTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String acme;
  private User hr1;
  private User hr2;
  private String hr1Token;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");
    hr1 = user(acme, UserRole.HR, "hr1@acme");
    hr2 = user(acme, UserRole.HR, "hr2@acme");
    hr1Token = token(hr1);
  }

  @Test
  void hrGeneratesCredentials_employeeSignsInAtLogin() throws Exception {
    Employee emp = approved(acme, hr1); // onboarded by hr1

    // Generate path: omit the password -> the server generates one.
    JsonNode result =
        json.readTree(
            mvc.perform(
                    post("/employees/" + emp.getId() + "/credentials")
                        .header("Authorization", "Bearer " + hr1Token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("localPart", "arjun"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(result.get("mailAddress").asText()).isEqualTo("arjun@acme");
    assertThat(result.get("emailedTo").asText()).isEqualTo(emp.getEmail()); // the personal email
    String password = result.get("password").asText(); // echoed once (dev)
    assertThat(password).isNotBlank();
    assertThat(auditLogs.findByAction("EMPLOYEE_CREDENTIALS_ASSIGNED")).hasSize(1);

    // Stored: address + a hash (never the plaintext).
    Employee saved = employees.findById(emp.getId()).orElseThrow();
    assertThat(saved.getMailAddress()).isEqualTo("arjun@acme");
    assertThat(saved.getPasswordHash()).isNotBlank().isNotEqualTo(password);

    // The employee signs in at the STAFF door with their mailbox address + password -> EMPLOYEE session.
    JsonNode session =
        json.readTree(
                mvc.perform(
                        post("/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                json.writeValueAsString(
                                    Map.of("email", "arjun@acme", "password", password))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("session");
    assertThat(session.get("type").asText()).isEqualTo("EMPLOYEE");
    assertThat(session.get("mailAddress").asText()).isEqualTo("arjun@acme");
    // The door is tagged so the web lands them in the portal (Stage 6) and a refresh stays stable.
    assertThat(session.get("authMethod").asText()).isEqualTo("PASSWORD");

    // A wrong password is a generic denial.
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "arjun@acme", "password", "nope"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void hrCanTypeThePasswordAndReissue() throws Exception {
    Employee emp = approved(acme, hr1);
    assign(emp, Map.of("localPart", "arjun", "password", "Chosen@Pass1")); // manual password

    // Re-issue with a new local part -> new address, re-emailed, audited again.
    JsonNode again = assign(emp, Map.of("localPart", "arjun.k"));
    assertThat(again.get("mailAddress").asText()).isEqualTo("arjun.k@acme");
    assertThat(auditLogs.findByAction("EMPLOYEE_CREDENTIALS_ASSIGNED")).hasSize(2);
  }

  @Test
  void theRecordExposesReadOnlyMailboxStateAndNeverThePassword() throws Exception {
    Employee emp = approved(acme, hr1);

    // Before assignment: the HR view drives the "Assign mailbox" button — not assigned, no address.
    JsonNode before = getRecord(emp);
    assertThat(before.get("credentialsAssigned").asBoolean()).isFalse();
    assertThat(before.path("mailAddress").isNull() || before.path("mailAddress").isMissingNode())
        .isTrue();
    // The record carries the COMPANY mail domain so the assign preview matches the address the server
    // will build (localpart@mailDomain) — not a guess from the acting HR's own login email.
    assertThat(before.get("mailDomain").asText()).isEqualTo("acme");

    assign(emp, Map.of("localPart", "arjun"));

    // After assignment: the view flips to the "Mailbox assigned" pill + address. The password/hash is
    // NEVER exposed on the record — only the one-time assign result echoes it (dev).
    JsonNode after = getRecord(emp);
    assertThat(after.get("credentialsAssigned").asBoolean()).isTrue();
    assertThat(after.get("mailAddress").asText()).isEqualTo("arjun@acme");
    assertThat(after.has("password")).isFalse();
    assertThat(after.toString()).doesNotContain("passwordHash").doesNotContain("password");

    // Reset (re-issue) keeps it assigned and reflects the new address.
    assign(emp, Map.of("localPart", "arjun.k"));
    JsonNode afterReset = getRecord(emp);
    assertThat(afterReset.get("credentialsAssigned").asBoolean()).isTrue();
    assertThat(afterReset.get("mailAddress").asText()).isEqualTo("arjun.k@acme");
  }

  @Test
  void readOnlyAndOtherRolesCannotAssignResetOrRevealViaTheHrEndpoints() throws Exception {
    Employee emp = approved(acme, hr1);
    assign(emp, Map.of("localPart", "arjun")); // give it a mailbox so "reset" is exercised too

    // Read-only viewers (Accountant, cross-company Accounts Admin) + the Manager share the record view but
    // must NEVER assign/reset credentials (HR-only write) nor use the HR reveal endpoint. The Accountant /
    // Accounts Admin keep their OWN audited reveal at /accountant/** (§2) — that is not this endpoint.
    User accountant = user(acme, UserRole.ACCOUNTANT, "acct@acme");
    User accountsAdmin = user(null, UserRole.ACCOUNTS_ADMIN, "aa@platform"); // companyId null, like Super Admin
    User manager = user(acme, UserRole.MANAGER, "mgr@acme");

    for (User u : new User[] {accountant, accountsAdmin, manager}) {
      String t = token(u);
      // Assign / reset mailbox credentials -> 403.
      mvc.perform(
              post("/employees/" + emp.getId() + "/credentials")
                  .header("Authorization", "Bearer " + t)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(Map.of("localPart", "x"))))
          .andExpect(status().isForbidden());
      // HR reveal endpoint -> 403.
      mvc.perform(post("/employees/" + emp.getId() + "/reveal").header("Authorization", "Bearer " + t))
          .andExpect(status().isForbidden());
    }

    // The employee was NOT mutated by any of those denied calls — still the original mailbox.
    assertThat(employees.findById(emp.getId()).orElseThrow().getMailAddress()).isEqualTo("arjun@acme");
  }

  @Test
  void onlyTheOnboardingHrAndOnlyAfterApprovalMayAssign() throws Exception {
    Employee mine = approved(acme, hr1);
    // A different HR (didn't onboard) -> 403.
    mvc.perform(
            post("/employees/" + mine.getId() + "/credentials")
                .header("Authorization", "Bearer " + token(hr2))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("localPart", "x"))))
        .andExpect(status().isForbidden());

    // Not yet approved -> 409 (credentials come only after Manager approval).
    Employee pending = new Employee();
    pending.setFullName("Pending Person");
    pending.setEmail("pending@ext.test");
    pending.setCompanyId(acme);
    pending.setOnboardingHrId(hr1.getId());
    pending.setStatus(EmployeeStatus.SUBMITTED);
    pending = employees.save(pending);
    mvc.perform(
            post("/employees/" + pending.getId() + "/credentials")
                .header("Authorization", "Bearer " + hr1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("localPart", "p"))))
        .andExpect(status().isConflict());
  }

  @Test
  void companyAdminAssignsAndResetsAnyApprovedEmployeeInTheirCompany() throws Exception {
    // Onboarded by hr1; the acting admin is NOT the onboarding HR -> proves company-wide scope (§6).
    Employee emp = approved(acme, hr1);
    User admin = user(acme, UserRole.COMPANY_ADMIN, "admin@acme");
    String adminToken = token(admin);

    // The admin finds the employee in the COMPANY-WIDE list (not limited to own-onboarded like HR).
    JsonNode list =
        json.readTree(
            mvc.perform(
                    get("/employees")
                        .param("search", "Arjun")
                        .param("status", "APPROVED")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(list.get("totalElements").asInt()).isEqualTo(1);

    // Assign a mailbox -> 201: address formed, hashed (never plaintext stored), emailed, audited.
    JsonNode result =
        json.readTree(
            mvc.perform(
                    post("/employees/" + emp.getId() + "/credentials")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("localPart", "arjun"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(result.get("mailAddress").asText()).isEqualTo("arjun@acme");
    assertThat(result.get("emailedTo").asText()).isEqualTo(emp.getEmail());
    String password = result.get("password").asText();
    assertThat(password).isNotBlank();
    assertThat(auditLogs.findByAction("EMPLOYEE_CREDENTIALS_ASSIGNED")).hasSize(1);

    Employee saved = employees.findById(emp.getId()).orElseThrow();
    assertThat(saved.getMailAddress()).isEqualTo("arjun@acme");
    assertThat(saved.getPasswordHash()).isNotBlank().isNotEqualTo(password);

    // The admin can read the record (company-scoped) — it shows the mailbox as assigned, no password.
    JsonNode record =
        json.readTree(
            mvc.perform(
                    get("/employees/" + emp.getId() + "/record")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(record.get("credentialsAssigned").asBoolean()).isTrue();
    assertThat(record.get("mailAddress").asText()).isEqualTo("arjun@acme");
    assertThat(record.toString()).doesNotContain("passwordHash").doesNotContain("password");

    // ...and RESET (re-issue) it -> new address, re-emailed, audited again.
    mvc.perform(
            post("/employees/" + emp.getId() + "/credentials")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("localPart", "arjun.k"))))
        .andExpect(status().isCreated());
    assertThat(employees.findById(emp.getId()).orElseThrow().getMailAddress()).isEqualTo("arjun.k@acme");
    assertThat(auditLogs.findByAction("EMPLOYEE_CREDENTIALS_ASSIGNED")).hasSize(2);
  }

  @Test
  void aCompanyAdminOfAnotherCompanyCannotAssign() throws Exception {
    Employee emp = approved(acme, hr1);
    String beta = company("BETA", "beta");
    User betaAdmin = user(beta, UserRole.COMPANY_ADMIN, "admin@beta");

    // Passes the role gate (is a COMPANY_ADMIN) but fails the service scope (different company) -> 403.
    mvc.perform(
            post("/employees/" + emp.getId() + "/credentials")
                .header("Authorization", "Bearer " + token(betaAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("localPart", "x"))))
        .andExpect(status().isForbidden());
    // ...and cannot read the record either (the read deliberately 404s out-of-scope, anti-enumeration).
    mvc.perform(
            get("/employees/" + emp.getId() + "/record")
                .header("Authorization", "Bearer " + token(betaAdmin)))
        .andExpect(status().isNotFound());

    assertThat(employees.findById(emp.getId()).orElseThrow().getMailAddress()).isNull();
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode getRecord(Employee emp) throws Exception {
    return json.readTree(
        mvc.perform(
                get("/employees/" + emp.getId() + "/record")
                    .header("Authorization", "Bearer " + hr1Token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode assign(Employee emp, Map<String, String> body) throws Exception {
    return json.readTree(
        mvc.perform(
                post("/employees/" + emp.getId() + "/credentials")
                    .header("Authorization", "Bearer " + hr1Token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
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

  private Employee approved(String companyId, User hr) {
    Employee e = new Employee();
    e.setEmployeeCode("ACME-EMP-000001");
    e.setFullName("Arjun Rao");
    e.setEmail("arjun.personal@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hr.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null));
  }
}
