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
import com.ihrms.domain.support.EmployeeCodes;
import com.ihrms.review.dto.ReviewDtos.ApproveRequest;
import com.ihrms.storage.StorageService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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
 * HR verification & approval (contract §3.3/§3.4) over the four-form model, storage MOCKED so it runs
 * in {@code ./mvnw package} with only a DB: the verification entry keys off the INTERNAL id; own-scope
 * (cross-HR/cross-company denied); verify each form + document (all verified auto-transitions to
 * HR_VERIFIED); sensitive values are masked and the reveal is audited; HR then APPROVES onto a team
 * (minting the code + notifying the team's manager) or terminally REJECTS.
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
  @Autowired ReviewService reviewService;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hr1;
  private User manager1;
  private String hr1Token;
  private String teamId; // hr1 + manager1's team
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
    teamId = team(companyA, hr1.getId(), manager1.getId());
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
    assertThat(body.get("form1").get("panNumber").asText()).isEqualTo("********"); // PAN surfaces under Form 1 (§3.2)
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
    assertThat(body.get("form1").get("panNumber").asText()).isEqualTo("ABCDE1234F");
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
  void verifyingEveryItemAutoVerifiesThenHrApprovesOntoTeam() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();

    // Approval is rejected (409) until the record is verified (status SUBMITTED, not HR_VERIFIED).
    approve(null).andExpect(status().isConflict());

    // Form 2 is HR-authored (§3.2) and NOT part of the gate — verifying Form 1 + the document completes the
    // review and AUTO-transitions the employee to HR_VERIFIED (no separate route/verify action).
    reviewForm("FORM1", "VERIFIED", null);
    MvcResult afterDoc = reviewDoc(docId, "VERIFIED", null);
    JsonNode afterDocBody = json.readTree(afterDoc.getResponse().getContentAsString());
    assertThat(afterDocBody.get("reviewComplete").asBoolean()).isTrue();
    assertThat(afterDocBody.get("status").asText()).isEqualTo("HR_VERIFIED"); // auto-transition
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.HR_VERIFIED);
    assertThat(auditLogs.findByAction("FORM_REVIEWED")).hasSize(1);
    assertThat(auditLogs.findByAction("DOCUMENT_REVIEWED")).hasSize(1);

    // HR APPROVES (no team choice): the server resolves the HR's own team, mints the ID, notifies that
    // team's manager, records the decision.
    MvcResult approved = approve("Looks good").andExpect(status().isOk()).andReturn();
    JsonNode result = json.readTree(approved.getResponse().getContentAsString());
    assertThat(result.get("status").asText()).isEqualTo("APPROVED");
    assertThat(result.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001"); // minted (§5)
    assertThat(result.get("teamId").asText()).isEqualTo(teamId); // resolved server-side = the HR's team
    assertThat(result.get("managerName").asText()).isEqualTo("mgr1@acme.test");

    Employee saved = employees.findById(emp.getId()).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(EmployeeStatus.APPROVED);
    assertThat(saved.getEmployeeCode()).isEqualTo("AAA-EMP-000001");

    // Decision record: an ApprovalRequest created + decided now (keeps the hierarchy metrics + Manager history).
    assertThat(approvals.findByEmployeeId(emp.getId()))
        .singleElement()
        .satisfies(a -> {
          assertThat(a.getStatus().name()).isEqualTo("APPROVED");
          assertThat(a.getManagerUserId()).isEqualTo(manager1.getId());
          assertThat(a.getHrUserId()).isEqualTo(hr1.getId());
          assertThat(a.getTeamId()).isEqualTo(teamId);
          assertThat(a.getNote()).isEqualTo("Looks good");
          assertThat(a.getDecidedAt()).isNotNull();
        });
    // The team's MANAGER (not HR) gets the durable bell that the employee joined their team.
    assertThat(notifications.findByRecipientUserId(manager1.getId()))
        .singleElement()
        .satisfies(n -> assertThat(n.getType().name()).isEqualTo("EMPLOYEE_APPROVED"));
    assertThat(notifications.findByRecipientUserId(hr1.getId())).isEmpty(); // HR gets no "decided" notice
    assertThat(auditLogs.findByAction("HR_APPROVED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));

    // Locked after approval: review + re-approve are rejected (409).
    mvc.perform(patch("/employees/" + emp.getId() + "/forms/FORM1")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("decision", "VERIFIED"))))
        .andExpect(status().isConflict());
    approve(null).andExpect(status().isConflict());
  }

  @Test
  void hrRejectsAVerifiedApplicationTerminallyWithNoManagerNotification() throws Exception {
    verifyEverything();
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.HR_VERIFIED);

    mvc.perform(post("/employees/" + emp.getId() + "/reject")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "Background check failed"))))
        .andExpect(status().isOk());

    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.REJECTED); // terminal
    assertThat(employees.findById(emp.getId()).orElseThrow().getEmployeeCode()).isNull(); // no code on reject
    assertThat(approvals.findByEmployeeId(emp.getId())).isEmpty(); // no decision row (joined no team)
    assertThat(notifications.findByRecipientUserId(manager1.getId())).isEmpty(); // manager never notified
    assertThat(auditLogs.findByAction("HR_REJECTED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getMetadata()).containsEntry("note", "Background check failed"));
  }

  @Test
  void approveScopeAndTheRetiredEndpointsAreGone() throws Exception {
    verifyEverything();

    // A MANAGER may not call the new HR approve/reject endpoints (403). No teamId is taken — the team is
    // resolved server-side, so there is no cross-company team input to validate.
    String managerToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                manager1.getId(), manager1.getEmail(), manager1.getName(), UserRole.MANAGER, companyA, null));
    mvc.perform(post("/employees/" + emp.getId() + "/approve")
            .header("Authorization", "Bearer " + managerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of())))
        .andExpect(status().isForbidden());

    // The retired routing + manager-decision endpoints are GONE (404).
    mvc.perform(post("/employees/" + emp.getId() + "/route-to-manager")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isNotFound());
  }

  @Test
  void rejectingADocumentRecordsTheReasonAndBlocksApproval() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();
    reviewForm("FORM1", "VERIFIED", null);
    MvcResult rejected = reviewDoc(docId, "REJECTED", "Scan is blurry");
    assertThat(json.readTree(rejected.getResponse().getContentAsString()).get("reviewComplete").asBoolean())
        .isFalse();
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.REJECTED);
    assertThat(auditLogs.findByAction("DOCUMENT_REVIEWED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getMetadata()).containsEntry("reason", "Scan is blurry"));

    // Not fully verified -> status is not HR_VERIFIED -> approval is rejected (409).
    approve(null).andExpect(status().isConflict());
  }

  @Test
  void decisionsAreNotLockedSoHrCanReDecideAfterRevising() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();

    // A form: verify -> reject -> verify again. Each PATCH succeeds (200, asserted in the helper)
    // and the stored status reflects the latest decision — an already-decided item is re-decidable.
    // (Form 1 is the example; Form 2 is HR-authored and not a review item.)
    reviewForm("FORM1", "VERIFIED", null);
    assertThat(form1s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.VERIFIED);
    reviewForm("FORM1", "REJECTED", "needs a fix");
    assertThat(form1s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.REJECTED);
    reviewForm("FORM1", "VERIFIED", null);
    assertThat(form1s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.VERIFIED);

    // A document: same — verifying then re-deciding to rejected is allowed.
    reviewDoc(docId, "VERIFIED", null);
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.VERIFIED);
    reviewDoc(docId, "REJECTED", "blurry scan");
    assertThat(documents.findById(docId).orElseThrow().getStatus()).isEqualTo(DocumentStatus.REJECTED);
  }

  @Test
  void sendingAnItemBackForRevisionFlagsItAndTheEmployeeAndBlocksApproval() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();
    reviewForm("FORM1", "VERIFIED", null);

    // Send the document back for revision — the note is required and surfaces on the record.
    MvcResult sentBack = reviewDoc(docId, "REVISION_REQUESTED", "Please re-upload a clearer PAN scan");
    JsonNode body = json.readTree(sentBack.getResponse().getContentAsString());
    assertThat(body.get("status").asText()).isEqualTo("REVISION_REQUESTED"); // derived from the item
    assertThat(body.get("reviewComplete").asBoolean()).isFalse();
    assertThat(body.get("documents").get(0).get("status").asText()).isEqualTo("REVISION_REQUESTED");
    assertThat(body.get("documents").get(0).get("revisionNote").asText())
        .isEqualTo("Please re-upload a clearer PAN scan");

    assertThat(documents.findById(docId).orElseThrow().getStatus())
        .isEqualTo(DocumentStatus.REVISION_REQUESTED);
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.REVISION_REQUESTED);

    // A flagged item blocks the approval decision (409 — not HR_VERIFIED).
    approve(null).andExpect(status().isConflict());

    // Verify ⇄ Send-back is re-decidable: re-verifying clears the note and AUTO-transitions the employee to
    // HR_VERIFIED (every item verified), which re-opens the approve/reject decision.
    MvcResult reVerified = reviewDoc(docId, "VERIFIED", null);
    assertThat(documents.findById(docId).orElseThrow().getRevisionNote()).isNull();
    assertThat(documents.findById(docId).orElseThrow().getRevisionRequestedAt()).isNull();
    assertThat(employees.findById(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.HR_VERIFIED);
    assertThat(json.readTree(reVerified.getResponse().getContentAsString()).get("reviewComplete").asBoolean())
        .isTrue();
  }

  @Test
  void form2IsNotAVerifiableReviewItem() throws Exception {
    // Form 2 is HR/SA-authored at onboard (§3.2) — attempting to verify/send it back is rejected (400).
    mvc.perform(patch("/employees/" + emp.getId() + "/forms/FORM2")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("decision", "VERIFIED"))))
        .andExpect(status().isBadRequest());
    // Its status is untouched by the attempt.
    assertThat(form2s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.SUBMITTED);
  }

  @Test
  void sendBackRequiresANote() throws Exception {
    mvc.perform(patch("/employees/" + emp.getId() + "/forms/FORM1")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("decision", "REVISION_REQUESTED"))))
        .andExpect(status().isBadRequest());
    assertThat(form1s.findByEmployeeId(emp.getId()).orElseThrow().getStatus())
        .isEqualTo(SectionStatus.SUBMITTED); // unchanged
  }

  @Test
  void parallelHrApprovalsAllocateDistinctWellFormedCodes() {
    IhrmsPrincipal.User hr =
        new IhrmsPrincipal.User(hr1.getId(), hr1.getEmail(), hr1.getName(), UserRole.HR, companyA, null);
    int n = 12;
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Employee e = new Employee();
      e.setFullName("Emp " + i);
      e.setEmail("emp" + i + "@p.test");
      e.setCompanyId(companyA);
      e.setOnboardingHrId(hr1.getId());
      e.setStatus(EmployeeStatus.HR_VERIFIED);
      employees.save(e);
      ids.add(e.getId());
    }

    // Approve all in parallel within the same company -> the atomic §5 sequence must not collide.
    Set<String> codes =
        ids.parallelStream()
            .map(id -> reviewService.approve(hr, id, new ApproveRequest(null), "127.0.0.1").employeeCode())
            .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));

    assertThat(codes).hasSize(n); // no duplicate codes under concurrency
    assertThat(codes).allMatch(c -> EmployeeCodes.EMPLOYEE_CODE.matcher(c).matches());
  }

  // --- helpers --------------------------------------------------------------

  /** POST the HR approve action (optional note; the team is resolved server-side) — un-asserted. */
  private org.springframework.test.web.servlet.ResultActions approve(String note) throws Exception {
    var payload = note == null ? Map.of() : Map.of("note", note);
    return mvc.perform(post("/employees/" + emp.getId() + "/approve")
        .header("Authorization", "Bearer " + hr1Token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(payload)));
  }

  /** Verify Form 1 + the single document so the employee auto-transitions to HR_VERIFIED. */
  private void verifyEverything() throws Exception {
    String docId = documents.findByEmployeeId(emp.getId()).get(0).getId();
    reviewForm("FORM1", "VERIFIED", null);
    reviewDoc(docId, "VERIFIED", null);
  }

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

  private String team(String companyId, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    return teams.save(t).getId();
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
