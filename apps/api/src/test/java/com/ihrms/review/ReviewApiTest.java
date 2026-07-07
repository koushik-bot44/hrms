package com.ihrms.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.NotificationRepository;
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
 * HR verification & routing (contract §3.3/§3.4) over the four-form model, storage MOCKED so it runs
 * in {@code ./mvnw package} with only a DB: the verification entry keys off the INTERNAL id; own-scope
 * (cross-HR/cross-company denied); verify each form + document; sensitive values are masked and the
 * reveal is audited; routing creates the ApprovalRequest + Manager Notification and sets HR_VERIFIED.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ReviewApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired Form1PersonalRepository form1s;
  @Autowired Form2InfoRepository form2s;
  @Autowired DocumentRepository documents;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hr1;
  private User manager1;
  private String hr1Token;
  private Employee emp; // onboarded by hr1, SUBMITTED, NO code, form1 + form2 + 1 PAN doc

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    hr1 = user(companyA, UserRole.HR, "hr1@acme.test");
    manager1 = user(companyA, UserRole.MANAGER, "mgr1@acme.test");
    team(companyA, hr1.getId(), manager1.getId());
    hr1Token = tokenFor(hr1);
    emp = submittedEmployee(hr1.getId());
    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://storage.local/get?sig=test");
  }

  @Test
  void hrOpensTheirOwnEmployeeRecordByIdCrossAccessDenied() throws Exception {
    MvcResult res =
        mvc.perform(get("/employees/" + emp.getId() + "/record").header("Authorization", "Bearer " + hr1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("id").asText()).isEqualTo(emp.getId());
    assertThat(body.get("employeeCode").isNull()).isTrue(); // no code pre-approval (§5)
    assertThat(body.get("fullName").asText()).isEqualTo("Evan Stone");
    assertThat(body.get("designation").asText()).isEqualTo("Software Engineer");
    assertThat(body.get("dateOfJoining").asText()).isEqualTo("2026-07-01");
    assertThat(body.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(body.get("reviewComplete").asBoolean()).isFalse();
    assertThat(body.get("form1").get("name").asText()).isEqualTo("Evan Stone");
    assertThat(body.get("form2").get("fullName").asText()).isEqualTo("Evan Stone");
    // Sensitive values are MASKED in the default view (§6).
    assertThat(body.get("form1").get("offeredCtc").asText()).isEqualTo("********");
    assertThat(body.get("form2").get("panNumber").asText()).isEqualTo("********");
    assertThat(body.get("documents")).hasSize(1);
    assertThat(body.get("documents").get(0).get("viewUrl").asText()).startsWith("http");
    assertThat(res.getResponse().getContentAsString()).doesNotContain("storageKey");
    assertThat(auditLogs.findByAction("EMPLOYEE_RECORD_VIEWED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));

    // Cross-HR (same company, different onboarder) and cross-company are both denied.
    String hr2 = tokenFor(user(companyA, UserRole.HR, "hr2@acme.test"));
    mvc.perform(get("/employees/" + emp.getId() + "/record").header("Authorization", "Bearer " + hr2))
        .andExpect(status().isNotFound());
    String companyB = company("BBB");
    String hr3 = tokenFor(user(companyB, UserRole.HR, "hr3@beta.test"));
    mvc.perform(get("/employees/" + emp.getId() + "/record").header("Authorization", "Bearer " + hr3))
        .andExpect(status().isNotFound());
  }

  @Test
  void revealingSensitiveValuesReturnsPlaintextAndIsAudited() throws Exception {
    MvcResult res =
        mvc.perform(post("/employees/" + emp.getId() + "/reveal").header("Authorization", "Bearer " + hr1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("form1").get("offeredCtc").asText()).isEqualTo("1500000");
    assertThat(body.get("form2").get("panNumber").asText()).isEqualTo("ABCDE1234F");
    assertThat(auditLogs.findByAction("SENSITIVE_FIELD_REVEALED")).hasSize(1);
  }

  @Test
  void lookupByCodeFindsApprovedOnly() throws Exception {
    Employee approved = approvedEmployee(hr1.getId(), "AAA-EMP-000007");
    MvcResult res =
        mvc.perform(get("/employees/lookup/AAA-EMP-000007").header("Authorization", "Bearer " + hr1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("id").asText()).isEqualTo(approved.getId());
    assertThat(body.get("employeeCode").asText()).isEqualTo("AAA-EMP-000007");
    assertThat(body.get("status").asText()).isEqualTo("APPROVED");

    mvc.perform(get("/employees/lookup/AAA-EMP-999999").header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isNotFound());
  }

  @Test
  void verifyingEveryItemCompletesReviewThenRoutesToManager() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();

    // Routing is rejected until everything is verified.
    mvc.perform(post("/employees/" + emp.getId() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isBadRequest());

    reviewForm("FORM1", "VERIFIED", null);
    reviewForm("FORM2", "VERIFIED", null);
    MvcResult afterDoc = reviewDoc(docId, "VERIFIED", null);
    assertThat(json.readTree(afterDoc.getResponse().getContentAsString()).get("reviewComplete").asBoolean())
        .isTrue();
    assertThat(auditLogs.findByAction("FORM_REVIEWED")).hasSize(2);
    assertThat(auditLogs.findByAction("DOCUMENT_REVIEWED")).hasSize(1);

    MvcResult routed =
        mvc.perform(post("/employees/" + emp.getId() + "/route-to-manager")
                .header("Authorization", "Bearer " + hr1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("note", "Looks good"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode result = json.readTree(routed.getResponse().getContentAsString());
    assertThat(result.get("status").asText()).isEqualTo("HR_VERIFIED");
    assertThat(result.get("managerName").asText()).isEqualTo("mgr1@acme.test");

    assertThat(approvals.findByEmployeeId(emp.getId()))
        .singleElement()
        .satisfies(a -> {
          assertThat(a.getManagerUserId()).isEqualTo(manager1.getId());
          assertThat(a.getHrUserId()).isEqualTo(hr1.getId());
          assertThat(a.getNote()).isEqualTo("Looks good");
        });
    assertThat(notifications.findByRecipientUserId(manager1.getId()))
        .singleElement()
        .satisfies(n -> assertThat(n.getType().name()).isEqualTo("APPROVAL_REQUESTED"));
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.HR_VERIFIED);
    assertThat(auditLogs.findByAction("APPROVAL_ROUTED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));

    // Locked after routing: review + re-route are rejected.
    mvc.perform(patch("/employees/" + emp.getId() + "/forms/FORM1")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("decision", "VERIFIED"))))
        .andExpect(status().isConflict());
    mvc.perform(post("/employees/" + emp.getId() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectingADocumentRecordsTheReasonAndBlocksRouting() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();
    reviewForm("FORM1", "VERIFIED", null);
    reviewForm("FORM2", "VERIFIED", null);
    MvcResult rejected = reviewDoc(docId, "REJECTED", "Scan is blurry");
    assertThat(json.readTree(rejected.getResponse().getContentAsString()).get("reviewComplete").asBoolean())
        .isFalse();
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.REJECTED);
    assertThat(auditLogs.findByAction("DOCUMENT_REVIEWED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getMetadata()).containsEntry("reason", "Scan is blurry"));

    mvc.perform(post("/employees/" + emp.getId() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isBadRequest());
  }

  @Test
  void decisionsAreNotLockedSoHrCanReDecideAfterRevising() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();

    // A form: verify -> reject -> verify again. Each PATCH succeeds (200, asserted in the helper)
    // and the stored status reflects the latest decision — an already-decided item is re-decidable.
    reviewForm("FORM2", "VERIFIED", null);
    assertThat(form2s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.VERIFIED);
    reviewForm("FORM2", "REJECTED", "needs a fix");
    assertThat(form2s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.REJECTED);
    reviewForm("FORM2", "VERIFIED", null);
    assertThat(form2s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.VERIFIED);

    // A document: same — verifying then re-deciding to rejected is allowed.
    reviewDoc(docId, "VERIFIED", null);
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.VERIFIED);
    reviewDoc(docId, "REJECTED", "blurry scan");
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.REJECTED);
  }

  // --- helpers --------------------------------------------------------------

  private void reviewForm(String form, String decision, String reason) throws Exception {
    var body = reason == null ? Map.of("decision", decision) : Map.of("decision", decision, "reason", reason);
    mvc.perform(patch("/employees/" + emp.getId() + "/forms/" + form)
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  private MvcResult reviewDoc(String docId, String decision, String reason) throws Exception {
    var body = reason == null ? Map.of("decision", decision) : Map.of("decision", decision, "reason", reason);
    return mvc.perform(patch("/employees/" + emp.getId() + "/documents/" + docId)
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk())
        .andReturn();
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

  private void team(String companyId, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    teams.save(t);
  }

  private Employee submittedEmployee(String hrId) {
    Employee e = new Employee();
    e.setFullName("Evan Stone");
    e.setEmail("evan@personal.test");
    e.setDesignation("Software Engineer");
    e.setDateOfJoining(LocalDate.parse("2026-07-01"));
    e.setCompanyId(companyA);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.SUBMITTED);
    // no employeeCode — allocated on approval (§5)
    employees.save(e);

    Form1Personal f1 = new Form1Personal();
    f1.setEmployeeId(e.getId());
    f1.setData(Map.of("name", "Evan Stone"));
    f1.setOfferedCtc("1500000"); // sensitive — encrypted at rest, masked in the record view
    f1.setStatus(SectionStatus.SUBMITTED);
    form1s.save(f1);

    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(e.getId());
    f2.setData(Map.of("fullName", "Evan Stone"));
    f2.setPanNumber("ABCDE1234F"); // sensitive
    f2.setStatus(SectionStatus.SUBMITTED);
    form2s.save(f2);

    Document d = new Document();
    d.setEmployeeId(e.getId());
    d.setDocType(DocumentType.PAN);
    d.setFileName("pan.pdf");
    d.setStorageKey("companies/x/employees/" + e.getId() + "/form4/pan.pdf");
    d.setMimeType("application/pdf");
    d.setSha256("deadbeef");
    d.setStatus(DocumentStatus.UPLOADED);
    documents.save(d);
    return e;
  }

  private Employee approvedEmployee(String hrId, String code) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName("Approved Person");
    e.setEmail("approved-" + code.toLowerCase() + "@personal.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyA);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), UserRole.HR, u.getCompanyId(), null));
  }
}
