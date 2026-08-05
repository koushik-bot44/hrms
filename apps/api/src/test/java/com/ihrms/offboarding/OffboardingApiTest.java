package com.ihrms.offboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
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

/**
 * Offboarding lifecycle stage 1 (§Offboarding) end to end: HR initiates (APPROVED-only, own-scope, one
 * active case), the HIERARCHY role sees a MINIMAL-PII pending inbox and approves/rejects, HR cancels; every
 * transition is audited and the right party is notified.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OffboardingApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired OffboardingCaseRepository cases;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  private String companyA;
  private User hr1;
  private String hr1Token;
  private User hierarchy;
  private String hierarchyToken;
  private Employee emp; // APPROVED, onboarded by hr1

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\","
            + "\"employee_agreements\",\"offboarding_cases\",\"approval_requests\",\"notifications\","
            + "\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("Globex Corporation", "GLBX");
    hr1 = user(companyA, UserRole.HR, "asha.hr@globex.test", "Asha Rao");
    team(companyA, hr1.getId(), "Engineering");
    hierarchy = user(null, UserRole.HIERARCHY, "overview@platform.test", "Platform Reviewer");
    hr1Token = tokenFor(hr1, UserRole.HR);
    hierarchyToken = tokenFor(hierarchy, UserRole.HIERARCHY);
    emp = approvedEmployee(hr1.getId(), "GLBX-EMP-000001");
  }

  // --- initiate -------------------------------------------------------------

  @Test
  void hrInitiatesCaseNotifiesHierarchyAndAudits() throws Exception {
    JsonNode body =
        json.readTree(initiate("Role eliminated", "2026-09-30").andExpect(status().isCreated()).andReturn()
            .getResponse().getContentAsString());
    assertThat(body.get("status").asText()).isEqualTo("PENDING_APPROVAL");
    assertThat(body.get("reason").asText()).isEqualTo("Role eliminated");
    assertThat(body.get("lastWorkingDay").asText()).isEqualTo("2026-09-30");
    assertThat(body.get("initiatedByName").asText()).isEqualTo("Asha Rao");
    assertThat(body.get("cancellable").asBoolean()).isTrue();

    assertThat(cases.findByStatusOrderByInitiatedAtDesc(OffboardingStatus.PENDING_APPROVAL)).hasSize(1);
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.APPROVED); // NOT touched in stage 1
    assertThat(notifications.findByRecipientUserId(hierarchy.getId()))
        .singleElement()
        .satisfies(n -> assertThat(n.getType().name()).isEqualTo("OFFBOARDING_INITIATED"));
    assertThat(auditLogs.findByAction("OFFBOARDING_INITIATED")).hasSize(1);
  }

  @Test
  void initiateRequiresApprovedEmployeeAndNoActiveCase() throws Exception {
    // Not APPROVED -> 409.
    Employee submitted = new Employee();
    submitted.setFullName("Not Approved");
    submitted.setEmail("na@globex.test");
    submitted.setCompanyId(companyA);
    submitted.setOnboardingHrId(hr1.getId());
    submitted.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(submitted);
    mvc.perform(post("/employees/" + submitted.getId() + "/offboarding/initiate")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", "x", "lastWorkingDay", "2026-09-30"))))
        .andExpect(status().isConflict());

    // Duplicate active case -> 409.
    initiate("first", "2026-09-30").andExpect(status().isCreated());
    initiate("second", "2026-10-30").andExpect(status().isConflict());
    assertThat(cases.findByStatusOrderByInitiatedAtDesc(OffboardingStatus.PENDING_APPROVAL)).hasSize(1);
  }

  @Test
  void initiateScopedToOnboardingHr() throws Exception {
    String foreignHr = tokenFor(user(companyA, UserRole.HR, "other@globex.test", "Other HR"), UserRole.HR);
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/initiate")
            .header("Authorization", "Bearer " + foreignHr)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("reason", "x", "lastWorkingDay", "2026-09-30"))))
        .andExpect(status().isNotFound());
    assertThat(cases.findByStatusOrderByInitiatedAtDesc(OffboardingStatus.PENDING_APPROVAL)).isEmpty();
  }

  // --- hierarchy inbox: minimal PII + role gate ----------------------------

  @Test
  void hierarchyPendingIsMinimalPiiAndHierarchyOnly() throws Exception {
    initiate("Restructuring", "2026-09-30").andExpect(status().isCreated());

    // Others cannot reach the hierarchy surface.
    mvc.perform(get("/hierarchy/offboarding/pending").header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isForbidden());

    String raw =
        mvc.perform(get("/hierarchy/offboarding/pending").header("Authorization", "Bearer " + hierarchyToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode rows = json.readTree(raw);
    assertThat(rows).hasSize(1);
    JsonNode row = rows.get(0);
    // The ONLY exposed fields (minimal-PII contract).
    assertThat(row.get("employeeName").asText()).isEqualTo("Meera Nair");
    assertThat(row.get("employeeCode").asText()).isEqualTo("GLBX-EMP-000001");
    assertThat(row.get("companyName").asText()).isEqualTo("Globex Corporation");
    assertThat(row.get("teamName").asText()).isEqualTo("Engineering");
    assertThat(row.get("reason").asText()).isEqualTo("Restructuring");
    assertThat(row.get("lastWorkingDay").asText()).isEqualTo("2026-09-30");
    assertThat(row.get("initiatedByName").asText()).isEqualTo("Asha Rao");
    assertThat(row.has("caseId")).isTrue();
    assertThat(row.has("initiatedAt")).isTrue();
    // Exactly nine fields — nothing else leaks.
    assertThat(row.size()).isEqualTo(9);
    // No employee PII beyond name/code: no email, no forms, no documents, no contact/address.
    assertThat(raw)
        .doesNotContain("meera@personal.test")
        .doesNotContain("panNumber")
        .doesNotContain("form1")
        .doesNotContain("currentAddress")
        .doesNotContain("aadhaar");
  }

  // --- approve / reject -----------------------------------------------------

  @Test
  void hierarchyApprovesTransitionsNotifiesHrAndBlocksRedecide() throws Exception {
    String caseId = initiateAndGetId("Redundancy", "2026-09-30");

    mvc.perform(post("/hierarchy/offboarding/" + caseId + "/approve")
            .header("Authorization", "Bearer " + hierarchyToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "Confirmed"))))
        .andExpect(status().isOk());

    assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(OffboardingStatus.APPROVED);
    assertThat(cases.findById(caseId).orElseThrow().getDecidedByUserId()).isEqualTo(hierarchy.getId());
    assertThat(notifications.findByRecipientUserId(hr1.getId()))
        .singleElement()
        .satisfies(n -> assertThat(n.getType().name()).isEqualTo("OFFBOARDING_APPROVED"));
    assertThat(auditLogs.findByAction("OFFBOARDING_APPROVED")).hasSize(1);

    // Re-deciding a decided case -> 409.
    mvc.perform(post("/hierarchy/offboarding/" + caseId + "/approve")
            .header("Authorization", "Bearer " + hierarchyToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of())))
        .andExpect(status().isConflict());
  }

  @Test
  void hierarchyRejectRequiresNoteIsTerminalAndFreesANewCase() throws Exception {
    String caseId = initiateAndGetId("Performance", "2026-09-30");

    // Reject without a note -> 400.
    mvc.perform(post("/hierarchy/offboarding/" + caseId + "/reject")
            .header("Authorization", "Bearer " + hierarchyToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of())))
        .andExpect(status().isBadRequest());

    // Reject with a note -> REJECTED (terminal), HR notified, audited.
    mvc.perform(post("/hierarchy/offboarding/" + caseId + "/reject")
            .header("Authorization", "Bearer " + hierarchyToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "Not this quarter"))))
        .andExpect(status().isOk());
    assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(OffboardingStatus.REJECTED);
    assertThat(notifications.findByRecipientUserId(hr1.getId()))
        .anySatisfy(n -> assertThat(n.getType().name()).isEqualTo("OFFBOARDING_REJECTED"));
    assertThat(auditLogs.findByAction("OFFBOARDING_REJECTED")).hasSize(1);

    // A rejected case is terminal -> HR may initiate a fresh one (no active case blocks it).
    initiate("Second attempt", "2026-10-30").andExpect(status().isCreated());
  }

  // --- cancel ---------------------------------------------------------------

  @Test
  void hrCancelsWhilePendingThenTerminalGuards() throws Exception {
    initiate("Reorg", "2026-09-30").andExpect(status().isCreated());
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/cancel")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "Retained"))))
        .andExpect(status().isOk());
    assertThat(cases.findFirstByEmployeeIdOrderByInitiatedAtDesc(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(OffboardingStatus.CANCELLED);
    assertThat(auditLogs.findByAction("OFFBOARDING_CANCELLED")).hasSize(1);

    // Nothing active to cancel now -> 409.
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/cancel")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of())))
        .andExpect(status().isConflict());
  }

  // --- record read ----------------------------------------------------------

  @Test
  void recordGetReturnsNullThenCase() throws Exception {
    JsonNode before =
        json.readTree(
            mvc.perform(get("/employees/" + emp.getId() + "/offboarding").header("Authorization", "Bearer " + hr1Token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(before.get("offboarding").isNull()).isTrue();

    initiate("Role eliminated", "2026-09-30").andExpect(status().isCreated());
    JsonNode after =
        json.readTree(
            mvc.perform(get("/employees/" + emp.getId() + "/offboarding").header("Authorization", "Bearer " + hr1Token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(after.get("offboarding").get("status").asText()).isEqualTo("PENDING_APPROVAL");
  }

  // --- helpers --------------------------------------------------------------

  private org.springframework.test.web.servlet.ResultActions initiate(String reason, String lwd)
      throws Exception {
    return mvc.perform(post("/employees/" + emp.getId() + "/offboarding/initiate")
        .header("Authorization", "Bearer " + hr1Token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("reason", reason, "lastWorkingDay", lwd))));
  }

  private String initiateAndGetId(String reason, String lwd) throws Exception {
    JsonNode body =
        json.readTree(initiate(reason, lwd).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    return body.get("id").asText();
  }

  private String company(String name, String code) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email, String name) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private void team(String companyId, String hrUserId, String name) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName(name);
    t.setHrUserId(hrUserId);
    teams.save(t);
  }

  private Employee approvedEmployee(String hrId, String code) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName("Meera Nair");
    e.setEmail("meera@personal.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyA);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private String tokenFor(User u, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), role, u.getCompanyId(), null));
  }
}
