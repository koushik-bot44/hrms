package com.ihrms.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * HR onboarding (ARCHITECTURE.md §3.2): the record is created with full name / email / designation /
 * date of joining, status INVITED and <strong>no employee ID</strong> (allocated on approval, §5); a
 * selection email is logged; email is globally unique; HR scoping + audit.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class EmployeesApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String companyId;
  private User hr;
  private String hrToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyId = company("ACME");
    hr = hrUser(companyId, "hr1@acme.test");
    hrToken = tokenFor(hr);
  }

  @Test
  void onboardCreatesInvitedRecordWithNoIdEmailsSelectionAndAudits(CapturedOutput output)
      throws Exception {
    MvcResult first =
        mvc.perform(asHr("Alex Doe", "Alex@Personal.TEST"))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode employee = json.readTree(first.getResponse().getContentAsString()).get("employee");
    assertThat(employee.get("employeeCode").isNull()).isTrue(); // no ID at onboarding (§5)
    assertThat(employee.get("fullName").asText()).isEqualTo("Alex Doe");
    assertThat(employee.get("email").asText()).isEqualTo("alex@personal.test"); // lower-cased
    assertThat(employee.get("designation").asText()).isEqualTo("Software Engineer");
    assertThat(employee.get("dateOfJoining").asText()).isEqualTo("2026-07-01");
    assertThat(employee.get("status").asText()).isEqualTo("INVITED");
    // The selection email fires (dev log) with the name + designation — and NO employee ID.
    assertThat(output.getOut())
        .contains("[DEV SELECTION]")
        .contains("Alex Doe")
        .contains("Software Engineer")
        .doesNotContain("EMP-");

    MvcResult second =
        mvc.perform(asHr("Sam Roe", "sam@personal.test")).andExpect(status().isCreated()).andReturn();
    assertThat(json.readTree(second.getResponse().getContentAsString()).get("loginUrl").asText())
        .contains("/employee/login");

    // GET lists both, newest first.
    JsonNode arr =
        json.readTree(
            mvc.perform(get("/employees").header("Authorization", "Bearer " + hrToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(arr.get("content")).hasSize(2);
    assertThat(arr.get("content").get(0).get("email").asText()).isEqualTo("sam@personal.test");

    assertThat(auditLogs.findByAction("EMPLOYEE_ONBOARDED"))
        .hasSize(2)
        .allSatisfy(
            row -> {
              assertThat(row.getCompanyId()).isEqualTo(companyId);
              assertThat(row.getActorType()).isEqualTo("USER");
              assertThat(row.getActorId()).isEqualTo(hr.getId());
            });
  }

  @Test
  void emailIsGloballyUnique() throws Exception {
    mvc.perform(asHr("Alex Doe", "dup@personal.test")).andExpect(status().isCreated());
    mvc.perform(asHr("Alex Twin", "dup@personal.test")).andExpect(status().isConflict());
  }

  @Test
  void hrSeesOnlyTheEmployeesItOnboarded() throws Exception {
    mvc.perform(asHr("E One", "e1@personal.test")).andExpect(status().isCreated());

    User otherHr = hrUser(companyId, "hr2@acme.test");
    String otherToken = tokenFor(otherHr);

    MvcResult mineList =
        mvc.perform(get("/employees").header("Authorization", "Bearer " + hrToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(mineList.getResponse().getContentAsString()).get("content")).hasSize(1);

    MvcResult othersList =
        mvc.perform(get("/employees").header("Authorization", "Bearer " + otherToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(othersList.getResponse().getContentAsString()).get("content")).isEmpty();
  }

  @Test
  void queueSupportsSearchAndStatusFilter() throws Exception {
    mvc.perform(asHr("Alice Wonder", "alice@personal.test")).andExpect(status().isCreated());
    mvc.perform(asHr("Bob Builder", "bob@personal.test")).andExpect(status().isCreated());

    // Name search (case-insensitive substring).
    JsonNode byName = queue(get("/employees").param("search", "alice"));
    assertThat(byName.get("content")).hasSize(1);
    assertThat(byName.get("content").get(0).get("fullName").asText()).isEqualTo("Alice Wonder");

    // Email search.
    assertThat(queue(get("/employees").param("search", "bob@")).get("content")).hasSize(1);

    // Employee-ID search: once a code is minted (on approval), HR can find by ID substring too.
    // Null codes (Bob is still INVITED) are LIKE-safe and simply don't match.
    jdbc.update(
        "UPDATE \"employees\" SET \"employeeCode\" = 'ACME-EMP-000042' WHERE lower(\"email\") = 'alice@personal.test'");
    JsonNode byCode = queue(get("/employees").param("search", "emp-000042"));
    assertThat(byCode.get("content")).hasSize(1);
    assertThat(byCode.get("content").get(0).get("fullName").asText()).isEqualTo("Alice Wonder");

    // Status filter: both are INVITED; APPROVED matches none.
    JsonNode invited = queue(get("/employees").param("status", "INVITED"));
    assertThat(invited.get("content")).hasSize(2);
    assertThat(invited.get("totalElements").asInt()).isEqualTo(2);
    assertThat(queue(get("/employees").param("status", "APPROVED")).get("content")).isEmpty();
  }

  private JsonNode queue(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
      throws Exception {
    return json.readTree(
        mvc.perform(req.header("Authorization", "Bearer " + hrToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  @Test
  void nonHrIsForbidden() throws Exception {
    String adminToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                "ca-1", "ca@acme.test", "CA", UserRole.COMPANY_ADMIN, companyId, null));
    mvc.perform(get("/employees").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isForbidden());
  }

  // --- fixtures -------------------------------------------------------------

  private MockHttpServletRequestBuilder asHr(String fullName, String email) throws Exception {
    return post("/employees")
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            json.writeValueAsString(
                Map.of(
                    "fullName", fullName,
                    "email", email,
                    "designation", "Software Engineer",
                    "dateOfJoining", "2026-07-01")));
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User hrUser(String companyId, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(UserRole.HR);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private String tokenFor(User user) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(
            user.getId(), user.getEmail(), user.getName(), UserRole.HR, user.getCompanyId(), null));
  }
}
