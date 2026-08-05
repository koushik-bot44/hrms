package com.ihrms.offboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.OffboardingDocument;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRequestRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.domain.repository.OffboardingLetterRepository;
import com.ihrms.domain.repository.RequestDocumentRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.time.LocalDate;
import java.util.List;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Offboarding letters as GENERATED, COMPANY-ISSUED documents (§3.6 stage 3): fidelity of the substituted text
 * (the settlement-cleared line stays static; pronouns BOTH ways; company + HR per a 2-company/2-HR matrix),
 * the all-verified gate, issue-resolves-an-open-request vs direct issue, re-issue overwrites, the upload
 * fallback, and the assertion that the employee has no write path for these types.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OffboardingLetterIssueTest {

  private static final byte[] FILE_BYTES = "letter".getBytes();

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired OffboardingCaseRepository cases;
  @Autowired OffboardingDocumentRepository docs;
  @Autowired OffboardingLetterRepository letters;
  @Autowired DocumentRequestRepository requests;
  @Autowired RequestDocumentRepository requestDocuments;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  // Company A: SCREATIVE-style joining company; HR "Asha Rao". Company B: the matrix's second tenant.
  private String companyA;
  private User hrA;
  private String hrAToken;
  private Employee empA;
  private String empAToken;
  private OffboardingCase caseA;

  private User hrB;
  private String hrBToken;
  private Employee empB;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"document_requests\",\"request_documents\","
            + "\"signatures\",\"generated_documents\",\"employee_agreements\",\"offboarding_letters\","
            + "\"offboarding_documents\",\"offboarding_clearance\",\"offboarding_cases\",\"approval_requests\","
            + "\"notifications\",\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");

    companyA = company("Globex Corporation", "GLBX");
    hrA = user(companyA, "asha.hr@globex.test", "Asha Rao");
    manager(companyA, hrA.getId());
    hrAToken = tokenFor(hrA);
    empA = approvedEmployee(companyA, hrA.getId(), "GLBX-EMP-000001", "Meera Nair");
    empAToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(empA.getId(), empA.getEmployeeCode(), empA.getEmail(), companyA));
    caseA = offboardingCase(empA.getId(), hrA.getId());

    String companyB = company("Initech Ltd", "INTC");
    hrB = user(companyB, "raj.hr@initech.test", "Raj Kumar");
    manager(companyB, hrB.getId());
    hrBToken = tokenFor(hrB);
    empB = approvedEmployee(companyB, hrB.getId(), "INTC-EMP-000001", "Sam Wilson");
    offboardingCase(empB.getId(), hrB.getId());

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://s/get");
    when(storage.presignedPutUrl(anyString(), anyString(), anyInt())).thenReturn("http://s/put");
    when(storage.getObjectBytes(any())).thenReturn(FILE_BYTES);
    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(inv -> "companies/" + inv.getArgument(0) + "/" + inv.getArgument(3));
  }

  // --- fidelity (via the preview endpoint's substituted bodyHtml) -----------

  @Test
  void relievingRendersCompanyHrAndKeepsSettlementLineStatic() throws Exception {
    String bodyA = preview(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null);
    // Company + issuing HR are the employee's, NOT the template's SCREATIVE / Ajay Bhaskar Reddy.
    assertThat(bodyA).contains("Globex Corporation").doesNotContain("SCREATIVE");
    assertThat(bodyA).contains("Asha Rao").doesNotContain("Ajay Bhaskar Reddy");
    // The full-and-final-settlement sentence is static text.
    assertThat(bodyA).contains("full and final settlement of account has been cleared");
    // Record + typed fields substituted.
    assertThat(bodyA).contains("Meera Nair").contains("GLBX-EMP-000001").contains("Senior Engineer");
    assertThat(bodyA).contains("04/05/2023"); // resignation date typed by HR

    // The matrix's second tenant renders ITS own company + HR — no cross-bleed.
    String bodyB = preview(empB.getId(), hrBToken, "RELIEVING_LETTER", relievingValues(), null);
    assertThat(bodyB).contains("Initech Ltd").contains("Raj Kumar").contains("Sam Wilson");
    assertThat(bodyB).doesNotContain("Globex Corporation").doesNotContain("Asha Rao");
  }

  @Test
  void experiencePronounsSubstituteBothWays() throws Exception {
    String male = preview(empA.getId(), hrAToken, "EXPERIENCE_LETTER", experienceValues(), "MALE");
    assertThat(male).contains("Mr. Meera Nair");
    assertThat(male).contains("He is a confident person");
    assertThat(male).contains("During his tenure");
    assertThat(male).doesNotContain("She is a confident person").doesNotContain("Ms. Meera Nair");

    String female = preview(empA.getId(), hrAToken, "EXPERIENCE_LETTER", experienceValues(), "FEMALE");
    assertThat(female).contains("Ms. Meera Nair");
    assertThat(female).contains("She is a confident person");
    assertThat(female).contains("During her tenure");
    assertThat(female).doesNotContain("He is a confident person").doesNotContain("Mr. Meera Nair");
  }

  // --- gate + issue + resolve/direct + re-issue -----------------------------

  @Test
  void issueGatedOnAllDocumentsVerified() throws Exception {
    // No documents / an unverified document -> 409.
    issue(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isConflict());
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.SUBMITTED);
    issue(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isConflict());
    // Verify -> issue succeeds.
    verifyDoc(OffboardingDocType.EXIT_FORMALITIES);
    issue(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isOk());
  }

  @Test
  void directIssuePersistsAndAuditsWithNoRequest() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    issue(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isOk());

    assertThat(letters.findByCaseIdAndType(caseA.getId(), RequestType.RELIEVING_LETTER))
        .isPresent()
        .get()
        .satisfies(l -> assertThat(l.getStorageKey()).endsWith("relieving_letter.pdf"));
    assertThat(auditLogs.findByAction("LETTER_ISSUED")).hasSize(1);
    // No employee request was created for a direct issue.
    assertThat(requests.findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(empA.getId(), RequestType.RELIEVING_LETTER))
        .isEmpty();

    // The employee sees the issued letter with a download link.
    JsonNode relieving = myLetter(empAToken, "RELIEVING_LETTER");
    assertThat(relieving.get("issued").asBoolean()).isTrue();
    assertThat(relieving.get("downloadUrl").asText()).startsWith("http");
  }

  @Test
  void issuingResolvesAnOpenEmployeeRequest() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    // Employee requests the experience letter first (open request routed to the case HR).
    mvc.perform(post("/me/offboarding/letters/EXPERIENCE_LETTER")
            .header("Authorization", "Bearer " + empAToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isOk());
    assertThat(requests.findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(empA.getId(), RequestType.EXPERIENCE_LETTER))
        .get()
        .satisfies(r -> assertThat(r.getStatus()).isEqualTo(RequestStatus.SUBMITTED));

    // HR issues -> the open request is RESOLVED with a bound (fulfilled) document.
    issue(empA.getId(), hrAToken, "EXPERIENCE_LETTER", experienceValues(), "MALE").andExpect(status().isOk());
    var req =
        requests.findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(empA.getId(), RequestType.EXPERIENCE_LETTER).orElseThrow();
    assertThat(req.getStatus()).isEqualTo(RequestStatus.RESOLVED);
    assertThat(requestDocuments.findByRequestIdOrderByCreatedAtAsc(req.getId()))
        .anySatisfy(d -> assertThat(d.getSha256()).isNotNull());
    assertThat(auditLogs.findByAction("REQUEST_RESOLVED")).hasSize(1);
  }

  @Test
  void reIssueOverwritesTheSameRow() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    issue(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isOk());
    String firstId = letters.findByCaseIdAndType(caseA.getId(), RequestType.RELIEVING_LETTER).orElseThrow().getId();

    issue(empA.getId(), hrAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isOk());
    // Still exactly one row (same id) — re-issue overwrote it, and there are two LETTER_ISSUED audit events.
    assertThat(letters.findByCaseId(caseA.getId())).hasSize(1);
    assertThat(letters.findByCaseIdAndType(caseA.getId(), RequestType.RELIEVING_LETTER).orElseThrow().getId())
        .isEqualTo(firstId);
    assertThat(auditLogs.findByAction("LETTER_ISSUED")).hasSize(2);
  }

  // --- upload fallback + no employee write path -----------------------------

  @Test
  void uploadFallbackStillFulfilsARequest() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    mvc.perform(post("/me/offboarding/letters/RELIEVING_LETTER")
            .header("Authorization", "Bearer " + empAToken)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk());

    JsonNode up =
        json.readTree(
            mvc.perform(post("/employees/" + empA.getId() + "/offboarding/letters/RELIEVING_LETTER/begin-upload")
                    .header("Authorization", "Bearer " + hrAToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fileName", "relieving.pdf", "contentType", "application/pdf", "sizeBytes", FILE_BYTES.length))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    mvc.perform(post("/employees/" + empA.getId() + "/offboarding/letters/RELIEVING_LETTER/resolve")
            .header("Authorization", "Bearer " + hrAToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("documentIds", List.of(up.get("documentId").asText())))))
        .andExpect(status().isOk());

    // Employee sees it fulfilled (downloadUrl present), even though no PDF was generated.
    JsonNode relieving = myLetter(empAToken, "RELIEVING_LETTER");
    assertThat(relieving.get("requestStatus").asText()).isEqualTo("RESOLVED");
    assertThat(relieving.get("downloadUrl").asText()).startsWith("http");
  }

  @Test
  void employeeHasNoIssueOrPreviewWritePath() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    // The issue + preview endpoints are HR-only; the employee token is forbidden.
    issue(empA.getId(), empAToken, "RELIEVING_LETTER", relievingValues(), null).andExpect(status().isForbidden());
    mvc.perform(post("/employees/" + empA.getId() + "/offboarding/letters/RELIEVING_LETTER/preview")
            .header("Authorization", "Bearer " + empAToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());
  }

  // --- helpers --------------------------------------------------------------

  private String preview(String employeeId, String token, String type, Map<String, String> values, String gender)
      throws Exception {
    JsonNode res =
        json.readTree(
            mvc.perform(post("/employees/" + employeeId + "/offboarding/letters/" + type + "/preview")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(body(values, gender))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    return res.get("bodyHtml").asText();
  }

  private ResultActions issue(String employeeId, String token, String type, Map<String, String> values, String gender)
      throws Exception {
    return mvc.perform(post("/employees/" + employeeId + "/offboarding/letters/" + type + "/issue")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body(values, gender))));
  }

  private JsonNode myLetter(String token, String type) throws Exception {
    JsonNode mine =
        json.readTree(
            mvc.perform(get("/me/offboarding/letters").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    return java.util.stream.StreamSupport.stream(mine.get("letters").spliterator(), false)
        .filter(l -> l.get("type").asText().equals(type))
        .findFirst()
        .orElseThrow();
  }

  private static Map<String, Object> body(Map<String, String> values, String gender) {
    return gender == null
        ? Map.of("hrValues", values)
        : Map.of("hrValues", values, "gender", gender);
  }

  private static Map<String, String> relievingValues() {
    return Map.of(
        "DATE", "16/04/2026",
        "RESIGNATION_DATE", "04/05/2023",
        "RELIEVING_DATE", "09/05/2023",
        "TENURE_FROM", "03/04/2023",
        "TENURE_TO", "09/05/2023",
        "DESIGNATION", "Senior Engineer");
  }

  private static Map<String, String> experienceValues() {
    return Map.of(
        "DATE", "26/12/2024",
        "DESIGNATION", "Senior Engineer",
        "TENURE_FROM", "19/06/2023",
        "TENURE_TO", "12/12/2024");
  }

  private void doc(OffboardingDocType type, OffboardingDocStatus status) {
    OffboardingDocument d = new OffboardingDocument();
    d.setCaseId(caseA.getId());
    d.setType(type);
    d.setStatus(status);
    d.setSentByUserId(hrA.getId());
    docs.save(d);
  }

  private void verifyDoc(OffboardingDocType type) {
    OffboardingDocument d = docs.findByCaseIdAndType(caseA.getId(), type).orElseThrow();
    d.setStatus(OffboardingDocStatus.VERIFIED);
    docs.save(d);
  }

  private String company(String name, String code) {
    Company cc = new Company();
    cc.setName(name);
    cc.setCode(code);
    return companies.save(cc).getId();
  }

  private User user(String companyId, String email, String name) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(UserRole.HR);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private void manager(String companyId, String hrUserId) {
    User mgr = new User();
    mgr.setEmail("mgr-" + hrUserId + "@x.test");
    mgr.setName("Mgr");
    mgr.setRole(UserRole.MANAGER);
    mgr.setCompanyId(companyId);
    users.save(mgr);
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(mgr.getId());
    teams.save(t);
  }

  private Employee approvedEmployee(String companyId, String hrId, String code, String fullName) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName(fullName);
    e.setEmail(code.toLowerCase() + "@personal.test");
    e.setDesignation("Engineer");
    e.setDateOfJoining(LocalDate.parse("2023-04-03"));
    e.setMailAddress(code.toLowerCase() + "@work.mail");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private OffboardingCase offboardingCase(String employeeId, String hrId) {
    OffboardingCase oc = new OffboardingCase();
    oc.setEmployeeId(employeeId);
    oc.setStatus(OffboardingStatus.APPROVED);
    oc.setReason("Resignation");
    oc.setLastWorkingDay(LocalDate.parse("2023-05-09"));
    oc.setInitiatedByUserId(hrId);
    return cases.save(oc);
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), UserRole.HR, u.getCompanyId(), null));
  }
}
