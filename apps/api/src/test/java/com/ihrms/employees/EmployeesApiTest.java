package com.ihrms.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import java.util.HashMap;
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
 * HR onboarding (ARCHITECTURE.md §3.2): HR FILLS FORM 2, which creates the record (status INVITED, no
 * employee ID — allocated on approval, §5) and sends the selection email to the personal email; the
 * personal email is globally unique; HR scoping + audit. Also covers the Form-2 edit while INVITED, the
 * IN_PROGRESS lock (409), and the re-invite on a personal-email change.
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
  void editForm2WhileInvitedSavesAndReinvitesOnPersonalEmailChange(CapturedOutput output)
      throws Exception {
    // Distinct (non-overlapping) local parts so the old address is NOT a substring of the new.
    String id = onboard("Alex Doe", "oldalex@personal.test");

    // A personal-email change re-invites the NEW address; other fields (designation) just save.
    Map<String, Object> body = form2Edit("Alex Doe", "freshalex@personal.test");
    body.put("designation", "Staff Engineer");
    JsonNode view =
        json.readTree(
            mvc.perform(patchForm2(id, body)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    assertThat(view.get("personalEmail").asText()).isEqualTo("freshalex@personal.test");
    assertThat(view.get("designation").asText()).isEqualTo("Staff Engineer");
    assertThat(view.get("employeeId").isNull()).isTrue(); // still greyed/blank until approval

    // The invite re-fired to the NEW address; the Employee's login identity moved with it.
    assertThat(output.getOut()).contains("[DEV SELECTION]").contains("freshalex@personal.test");
    assertThat(auditLogs.findByAction("EMPLOYEE_REINVITED")).hasSize(1);
    assertThat(queue(get("/employees").param("search", "freshalex@")).get("content")).hasSize(1);
    assertThat(queue(get("/employees").param("search", "oldalex@")).get("content")).isEmpty();
  }

  @Test
  void editForm2NonEmailChangeDoesNotReinvite() throws Exception {
    String id = onboard("Alex Doe", "alex@personal.test");
    Map<String, Object> body = form2Edit("Alex Doe", "alex@personal.test"); // same email
    body.put("designation", "Staff Engineer");
    mvc.perform(patchForm2(id, body)).andExpect(status().isOk());
    assertThat(auditLogs.findByAction("EMPLOYEE_REINVITED")).isEmpty(); // no email change → no re-invite
    assertThat(auditLogs.findByAction("FORM2_UPDATED")).hasSize(1);
  }

  @Test
  void editForm2IsLockedOnceTheEmployeeStartsOnboarding() throws Exception {
    String id = onboard("Alex Doe", "alex@personal.test");
    // Simulate the employee starting their forms (INVITED -> IN_PROGRESS): Form 2 becomes read-only.
    jdbc.update("UPDATE \"employees\" SET \"status\" = 'IN_PROGRESS' WHERE \"id\" = ?", id);
    mvc.perform(patchForm2(id, form2Edit("Alex Doe", "alex@personal.test")))
        .andExpect(status().isConflict());
  }

  @Test
  void editForm2EnforcesPersonalEmailUniqueness() throws Exception {
    String alex = onboard("Alex Doe", "alex@personal.test");
    onboard("Bob Roe", "bob@personal.test");
    mvc.perform(patchForm2(alex, form2Edit("Alex Doe", "bob@personal.test")))
        .andExpect(status().isConflict()); // bob's email is taken
  }

  @Test
  void nonHrNonAdminIsForbiddenFromTheEmployeeList() throws Exception {
    // The list is HR (own onboarded) + COMPANY_ADMIN (company-wide, §6). A MANAGER — like any other
    // role — is denied. (COMPANY_ADMIN's company-wide access is covered in EmployeeCredentialsTest.)
    String managerToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                "mgr-1", "mgr@acme.test", "Mgr", UserRole.MANAGER, companyId, null));
    mvc.perform(get("/employees").header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isForbidden());
  }

  // --- fixtures -------------------------------------------------------------

  private MockHttpServletRequestBuilder asHr(String fullName, String email) throws Exception {
    // Onboard = HR fills Form 2 (§3.2); the personal email is the login identity.
    return post("/employees")
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("form2", form2Body(fullName, email))));
  }

  private static Map<String, Object> form2Body(String fullName, String personalEmail) {
    return Map.of(
        "fullName", fullName,
        "personalEmail", personalEmail,
        "designation", "Software Engineer",
        "dateOfJoining", "2026-07-01");
  }

  /** Onboard via HR and return the new employee's internal id. */
  private String onboard(String fullName, String email) throws Exception {
    MvcResult r = mvc.perform(asHr(fullName, email)).andExpect(status().isCreated()).andReturn();
    return json.readTree(r.getResponse().getContentAsString()).get("employee").get("id").asText();
  }

  /** A mutable Form-2 edit body (so tests can add/override fields like designation). */
  private static Map<String, Object> form2Edit(String fullName, String personalEmail) {
    return new HashMap<>(form2Body(fullName, personalEmail));
  }

  private MockHttpServletRequestBuilder patchForm2(String employeeId, Map<String, Object> body)
      throws Exception {
    return patch("/employees/" + employeeId + "/form2")
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
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
