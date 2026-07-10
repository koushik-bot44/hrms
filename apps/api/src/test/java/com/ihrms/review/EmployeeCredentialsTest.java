package com.ihrms.review;

import static org.assertj.core.api.Assertions.assertThat;
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
 * /login}. Scoped to the acting HR's own onboarded employee; audited.
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

  // --- helpers --------------------------------------------------------------

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
