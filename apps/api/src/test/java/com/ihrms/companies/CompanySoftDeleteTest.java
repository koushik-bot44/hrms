package com.ihrms.companies;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.repository.AuditLogRepository;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Company archival (soft-delete) — ARCHITECTURE.md §2/§6/§7. Deleting a company excludes it from the
 * active list, denies all its principals (OTP login + already-issued tokens), retains + still exposes
 * its audit trail to Super Admin, blocks active ops, and is reversible via restore. The Super Admin
 * (null companyId) is never affected. Child rows + reserved emails are untouched.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class CompanySoftDeleteTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired AuditLogRepository auditLogs;
  @Autowired AccountEmails accountEmails;
  @Autowired JdbcTemplate jdbc;

  private String superToken;
  private Company company;
  private User admin; // COMPANY_ADMIN under `company`
  private User hr;
  private Employee employee;
  private String adminToken;
  private String employeeToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    User superAdmin = staff("Super Admin", "super@root.test", UserRole.SUPER_ADMIN, null);
    superToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                superAdmin.getId(), superAdmin.getEmail(), superAdmin.getName(),
                UserRole.SUPER_ADMIN, null, null));

    company = new Company();
    company.setName("Acme Inc");
    company.setCode("ACME");
    companies.save(company);

    admin = staff("Cara Admin", "ca@acme.test", UserRole.COMPANY_ADMIN, company.getId());
    hr = staff("Hana HR", "hr@acme.test", UserRole.HR, company.getId());
    adminToken = tokenFor(admin);

    employee = new Employee();
    employee.setFullName("Eve Employee");
    employee.setEmail("eve@personal.test");
    employee.setCompanyId(company.getId());
    employee.setOnboardingHrId(hr.getId());
    employees.save(employee);
    employeeToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(
                employee.getId(), null, employee.getEmail(), company.getId()));
  }

  @Test
  void deleteArchivesExcludesFromActiveListAndAudits() throws Exception {
    MvcResult res =
        mvc.perform(delete("/companies/" + company.getId()).header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("status").asText()).isEqualTo("DELETED");
    assertThat(body.get("deletedAt").isNull()).isFalse();

    // Excluded from the active list; present in the deleted list.
    assertThat(activeCompanyIds()).doesNotContain(company.getId());
    assertThat(deletedCompanyIds()).contains(company.getId());

    Company reloaded = companies.findById(company.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo("DELETED");
    assertThat(reloaded.getDeletedAt()).isNotNull();
    assertThat(reloaded.getDeletedByUserId()).isNotNull();
    assertThat(auditLogs.findByAction("COMPANY_DELETED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(company.getId()));

    // No child rows were removed (soft-delete only).
    assertThat(users.findById(admin.getId())).isPresent();
    assertThat(employees.findById(employee.getId())).isPresent();
  }

  @Test
  void principalsOfDeletedCompanyAreDeniedLoginAndExistingTokensRejected() throws Exception {
    archive();

    // OTP login is denied (generic response, no devOtp) for staff + employee of the archived company.
    assertThat(requestOtp("Cara Admin", "ca@acme.test").has("devOtp")).isFalse();
    assertThat(requestOtp("Hana HR", "hr@acme.test").has("devOtp")).isFalse();
    assertThat(requestOtp("Eve Employee", "eve@personal.test").has("devOtp")).isFalse();

    // Already-issued access tokens stop working on the very next request.
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + employeeToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void superAdminIsUnaffectedByDeletion() throws Exception {
    archive();
    // The Super Admin (null companyId) still authenticates and operates — no self-lockout.
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + superToken)).andExpect(status().isOk());
    mvc.perform(get("/companies").header("Authorization", "Bearer " + superToken)).andExpect(status().isOk());
  }

  @Test
  void deletedCompanyAuditRemainsViewableFlagged() throws Exception {
    archive();
    MvcResult res =
        mvc.perform(
                get("/audit?companyId=" + company.getId())
                    .header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("companyDeleted").asBoolean()).isTrue();
    assertThat(body.get("totalElements").asInt()).isPositive(); // COMPANY_DELETED row retained
  }

  @Test
  void restoreReactivatesAndRestoresLogin() throws Exception {
    archive();
    mvc.perform(post("/companies/" + company.getId() + "/restore").header("Authorization", "Bearer " + superToken))
        .andExpect(status().isOk());

    Company reloaded = companies.findById(company.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo("ACTIVE");
    assertThat(reloaded.getDeletedAt()).isNull();
    assertThat(auditLogs.findByAction("COMPANY_RESTORED")).hasSize(1);

    // Login works again, and the previously-rejected token is accepted again.
    assertThat(requestOtp("Cara Admin", "ca@acme.test").get("devOtp").asText()).hasSize(6);
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
    assertThat(activeCompanyIds()).contains(company.getId());
  }

  @Test
  void archivedCompanyBlocksUpdateAndProvisionButEmailsStayReserved() throws Exception {
    archive();
    mvc.perform(
            patch("/companies/" + company.getId())
                .header("Authorization", "Bearer " + superToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "Renamed"))))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/companies/" + company.getId() + "/admin")
                .header("Authorization", "Bearer " + superToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "New Admin", "email", "new@acme.test"))))
        .andExpect(status().isConflict());

    // The archived company's emails remain reserved (clean restore, no collisions): the staff email
    // still blocks an employee reusing it, and the employee email still blocks a staff account.
    assertThatThrownBy(() -> accountEmails.assertAvailableForEmployee("ca@acme.test"))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> accountEmails.assertAvailableForStaff("eve@personal.test"))
        .isInstanceOf(ResponseStatusException.class);
  }

  // --- helpers --------------------------------------------------------------

  private void archive() throws Exception {
    mvc.perform(delete("/companies/" + company.getId()).header("Authorization", "Bearer " + superToken))
        .andExpect(status().isOk());
  }

  private JsonNode requestOtp(String fullName, String email) throws Exception {
    MvcResult res =
        mvc.perform(
                post("/auth/request-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fullName", fullName, "email", email))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private java.util.List<String> activeCompanyIds() throws Exception {
    return companyIds("/companies");
  }

  private java.util.List<String> deletedCompanyIds() throws Exception {
    return companyIds("/companies?deleted=true");
  }

  private java.util.List<String> companyIds(String path) throws Exception {
    MvcResult res =
        mvc.perform(get(path).header("Authorization", "Bearer " + superToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode arr = json.readTree(res.getResponse().getContentAsString());
    java.util.List<String> ids = new java.util.ArrayList<>();
    arr.forEach(c -> ids.add(c.get("id").asText()));
    return ids;
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

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), u.getTeamId()));
  }
}
