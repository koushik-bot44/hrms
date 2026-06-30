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
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.ProfileSection;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.ProfileSectionRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
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
 * HR verification & routing (contract §3.3/§3.4), storage MOCKED so it runs in {@code ./mvnw
 * package} with only a DB: own-scope lookup (cross-HR/cross-company denied), verify/reject persists,
 * routing creates the ApprovalRequest + Manager Notification and sets HR_VERIFIED, gating, and audit.
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
  @Autowired ProfileSectionRepository sections;
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
  private Employee emp; // onboarded by hr1, SUBMITTED, with 2 sections + 1 PAN doc

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    hr1 = user(companyA, UserRole.HR, "hr1@acme.test");
    manager1 = user(companyA, UserRole.MANAGER, "mgr1@acme.test");
    team(companyA, hr1.getId(), manager1.getId());
    hr1Token = tokenFor(hr1);
    emp = submittedEmployee(hr1.getId(), "AAA-EMP-000001");
    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://storage.local/get?sig=test");
  }

  @Test
  void hrSeesOnlyTheirOwnOnboardedEmployeeRecord() throws Exception {
    MvcResult res =
        mvc.perform(get("/employees/" + emp.getEmployeeCode()).header("Authorization", "Bearer " + hr1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(body.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(body.get("reviewComplete").asBoolean()).isFalse();
    assertThat(body.get("sections")).hasSize(2);
    assertThat(body.get("documents")).hasSize(1);
    assertThat(body.get("documents").get(0).get("viewUrl").asText()).startsWith("http");
    assertThat(res.getResponse().getContentAsString()).doesNotContain("storageKey");
    assertThat(auditLogs.findByAction("EMPLOYEE_RECORD_VIEWED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));

    // Cross-HR (same company, different onboarder) and cross-company are both denied.
    String hr2 = tokenFor(user(companyA, UserRole.HR, "hr2@acme.test"));
    mvc.perform(get("/employees/" + emp.getEmployeeCode()).header("Authorization", "Bearer " + hr2))
        .andExpect(status().isNotFound());
    String companyB = company("BBB");
    String hr3 = tokenFor(user(companyB, UserRole.HR, "hr3@beta.test"));
    mvc.perform(get("/employees/" + emp.getEmployeeCode()).header("Authorization", "Bearer " + hr3))
        .andExpect(status().isNotFound());
  }

  @Test
  void verifyingEveryItemCompletesReviewThenRoutesToManager() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();

    // Routing is rejected until everything is verified.
    mvc.perform(post("/employees/" + emp.getEmployeeCode() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isBadRequest());

    review("sections/PERSONAL", "VERIFIED", null);
    review("sections/GOVERNMENT", "VERIFIED", null);
    MvcResult afterDoc = reviewDoc(docId, "VERIFIED", null);
    assertThat(json.readTree(afterDoc.getResponse().getContentAsString()).get("reviewComplete").asBoolean())
        .isTrue();
    assertThat(auditLogs.findByAction("SECTION_REVIEWED")).hasSize(2);
    assertThat(auditLogs.findByAction("DOCUMENT_REVIEWED")).hasSize(1);

    // Route to the team's Manager.
    MvcResult routed =
        mvc.perform(post("/employees/" + emp.getEmployeeCode() + "/route-to-manager")
                .header("Authorization", "Bearer " + hr1Token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("note", "Looks good"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode result = json.readTree(routed.getResponse().getContentAsString());
    assertThat(result.get("status").asText()).isEqualTo("HR_VERIFIED");
    assertThat(result.get("managerName").asText()).isEqualTo("mgr1@acme.test");

    // ApprovalRequest -> the team's Manager; Notification -> the Manager's inbox; employee HR_VERIFIED.
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
    mvc.perform(patch("/employees/" + emp.getEmployeeCode() + "/sections/PERSONAL")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("decision", "VERIFIED"))))
        .andExpect(status().isConflict());
    mvc.perform(post("/employees/" + emp.getEmployeeCode() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectingADocumentRecordsTheReasonAndBlocksRouting() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();
    review("sections/PERSONAL", "VERIFIED", null);
    review("sections/GOVERNMENT", "VERIFIED", null);
    MvcResult rejected = reviewDoc(docId, "REJECTED", "Scan is blurry");
    assertThat(json.readTree(rejected.getResponse().getContentAsString()).get("reviewComplete").asBoolean())
        .isFalse();
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.REJECTED);
    assertThat(auditLogs.findByAction("DOCUMENT_REVIEWED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getMetadata()).containsEntry("reason", "Scan is blurry"));

    mvc.perform(post("/employees/" + emp.getEmployeeCode() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isBadRequest());
  }

  // --- helpers --------------------------------------------------------------

  private void review(String pathSuffix, String decision, String reason) throws Exception {
    var body = reason == null ? Map.of("decision", decision) : Map.of("decision", decision, "reason", reason);
    mvc.perform(patch("/employees/" + emp.getEmployeeCode() + "/" + pathSuffix)
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
  }

  private MvcResult reviewDoc(String docId, String decision, String reason) throws Exception {
    var body = reason == null ? Map.of("decision", decision) : Map.of("decision", decision, "reason", reason);
    return mvc.perform(patch("/employees/" + emp.getEmployeeCode() + "/documents/" + docId)
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

  private Employee submittedEmployee(String hrId, String code) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setEmail("evan@personal.test");
    e.setCompanyId(companyA);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(e);

    section(e.getId(), SectionKey.PERSONAL, Map.of("fullName", "Evan Stone"));
    section(e.getId(), SectionKey.GOVERNMENT, Map.of("panNumber", "ABCDE1234F"));

    Document d = new Document();
    d.setEmployeeId(e.getId());
    d.setSectionKey(SectionKey.GOVERNMENT);
    d.setDocType(DocumentType.PAN);
    d.setFileName("pan.pdf");
    d.setStorageKey("companies/x/employees/" + e.getId() + "/GOVERNMENT/pan.pdf");
    d.setMimeType("application/pdf");
    d.setSha256("deadbeef");
    d.setStatus(DocumentStatus.UPLOADED);
    documents.save(d);
    return e;
  }

  private void section(String employeeId, SectionKey key, Map<String, Object> data) {
    ProfileSection s = new ProfileSection();
    s.setEmployeeId(employeeId);
    s.setKey(key);
    s.setData(data);
    s.setStatus(SectionStatus.SUBMITTED);
    sections.save(s);
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), UserRole.HR, u.getCompanyId(), null));
  }
}
