package com.ihrms.offboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.storage.StorageService;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Offboarding documents (§3.6 stage 2) end to end: HR sends per-case documents (APPROVED-case-only, per-type
 * idempotency, required-value validation); the employee fills/signs in the workspace; the HR verify/send-back
 * loop; the HR-only clearance checklist; substitution across companies.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OffboardingDocApiTest {

  private static final String PNG_1X1 =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired EmployeeRepository employees;
  @Autowired Form1PersonalRepository form1s;
  @Autowired Form2InfoRepository form2s;
  @Autowired OffboardingCaseRepository cases;
  @Autowired OffboardingDocumentRepository docs;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hr1;
  private String hr1Token;
  private Employee emp;
  private String empToken;
  private OffboardingCase approvedCase;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\","
            + "\"employee_agreements\",\"offboarding_documents\",\"offboarding_clearance\","
            + "\"offboarding_cases\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("Globex Corporation", "GLBX");
    hr1 = user(companyA, UserRole.HR, "asha.hr@globex.test", "Asha Rao");
    team(companyA, hr1.getId());
    hr1Token = tokenFor(hr1);
    emp = approvedEmployee(hr1.getId(), "GLBX-EMP-000001");
    empToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(emp.getId(), emp.getEmployeeCode(), emp.getEmail(), companyA));
    approvedCase = offboardingCase(emp.getId(), hr1.getId(), OffboardingStatus.APPROVED);

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://s/get?sig=t");
    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            inv ->
                "companies/" + inv.getArgument(0) + "/employees/" + inv.getArgument(1) + "/"
                    + inv.getArgument(2) + "/" + inv.getArgument(3));
  }

  // --- send -----------------------------------------------------------------

  @Test
  void sendRequiresApprovedCaseValidatesValuesAndIsPerTypeIdempotent() throws Exception {
    // A PENDING_APPROVAL case cannot receive documents.
    approvedCase.setStatus(OffboardingStatus.PENDING_APPROVAL);
    cases.save(approvedCase);
    sendDocs(settlement()).andExpect(status().isConflict());
    approvedCase.setStatus(OffboardingStatus.APPROVED);
    cases.save(approvedCase);

    // Missing required HR values -> 400.
    sendDocs(Map.of("type", "SETTLEMENT", "hrValues", Map.of())).andExpect(status().isBadRequest());

    // Send only EXIT_FORMALITIES (no HR values) -> 201, 1 row.
    sendDocs(Map.of("type", "EXIT_FORMALITIES", "hrValues", Map.of())).andExpect(status().isCreated());
    assertThat(docs.findByCaseId(approvedCase.getId())).hasSize(1);
    assertThat(auditLogs.findByAction("OFFBOARDING_DOCS_SENT")).hasSize(1);

    // Send remainder (EXIT already exists) -> creates only Settlement, no duplicate.
    sendDocs(Map.of("type", "EXIT_FORMALITIES", "hrValues", Map.of()), settlement())
        .andExpect(status().isCreated());
    assertThat(docs.findByCaseId(approvedCase.getId())).hasSize(2);

    // Full duplicate -> 409.
    sendDocs(Map.of("type", "EXIT_FORMALITIES", "hrValues", Map.of())).andExpect(status().isConflict());
  }

  @Test
  void sendScopedToOnboardingHr() throws Exception {
    String foreign = tokenFor(user(companyA, UserRole.HR, "other@globex.test", "Other HR"));
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/documents/send")
            .header("Authorization", "Bearer " + foreign)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("documents", List.of(settlement())))))
        .andExpect(status().isNotFound());
  }

  // --- employee complete + HR verify/send-back ------------------------------

  @Test
  void employeeCompletesSettlementThenHrVerifies() throws Exception {
    sendDocs(settlement()).andExpect(status().isCreated());

    var res =
        complete(
                "SETTLEMENT",
                Map.of(
                    "consentAccepted", true,
                    "fillValues", Map.of("FATHER_NAME", "Rajan Nair", "AGE", "31", "ADDRESS", "12 Marine Drive"),
                    "signatureDataUrl", PNG_1X1))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(res.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("SUBMITTED");

    var saved = docs.findByCaseIdAndType(approvedCase.getId(), OffboardingDocType.SETTLEMENT).orElseThrow();
    assertThat(saved.getStatus()).isEqualTo(OffboardingDocStatus.SUBMITTED);
    assertThat(saved.getStorageKey()).endsWith("/offboarding/settlement.pdf");
    assertThat(notifications.findByRecipientUserId(hr1.getId()))
        .anySatisfy(n -> assertThat(n.getType().name()).isEqualTo("OFFBOARDING_DOC_SUBMITTED"));
    assertThat(auditLogs.findByAction("OFFBOARDING_DOC_SUBMITTED")).hasSize(1);

    // The rendered PDF carries the company name + the terms line + the employee fills.
    ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
    verify(storage, atLeastOnce()).putObject(anyString(), pdf.capture(), anyString());
    String t = text(pdf.getValue());
    assertThat(t).contains("Globex Corporation").doesNotContain("SCREATIVES");
    assertThat(t).contains("Rajan Nair").contains("Rs.10,00,000/-");

    // HR verifies.
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/documents/SETTLEMENT/verify")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isOk());
    assertThat(docs.findByCaseIdAndType(approvedCase.getId(), OffboardingDocType.SETTLEMENT).orElseThrow().getStatus())
        .isEqualTo(OffboardingDocStatus.VERIFIED);
    assertThat(auditLogs.findByAction("OFFBOARDING_DOC_VERIFIED")).hasSize(1);
  }

  @Test
  void sendBackThenResubmitReturnsToSubmitted() throws Exception {
    sendDocs(Map.of("type", "EXIT_FORMALITIES", "hrValues", Map.of())).andExpect(status().isCreated());
    complete("EXIT_FORMALITIES", Map.of("consentAccepted", true, "signatureDataUrl", PNG_1X1))
        .andExpect(status().isOk());

    // HR sends it back with a note (SUBMITTED -> REVISION_REQUESTED); the note reaches the employee view.
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/documents/EXIT_FORMALITIES/send-back")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "Please re-read section 2"))))
        .andExpect(status().isOk());
    assertThat(auditLogs.findByAction("OFFBOARDING_DOC_SENT_BACK")).hasSize(1);
    JsonNode view =
        json.readTree(
            mvc.perform(get("/me/offboarding/EXIT_FORMALITIES").header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(view.get("status").asText()).isEqualTo("REVISION_REQUESTED");
    assertThat(view.get("revisionNote").asText()).isEqualTo("Please re-read section 2");

    // Employee resubmits -> back to SUBMITTED (same stable key), note cleared.
    complete("EXIT_FORMALITIES", Map.of("consentAccepted", true, "signatureDataUrl", PNG_1X1))
        .andExpect(status().isOk());
    var d = docs.findByCaseIdAndType(approvedCase.getId(), OffboardingDocType.EXIT_FORMALITIES).orElseThrow();
    assertThat(d.getStatus()).isEqualTo(OffboardingDocStatus.SUBMITTED);
    assertThat(d.getRevisionNote()).isNull();

    // Verify/send-back only apply to a SUBMITTED doc — verifying a PENDING one 409s.
    sendDocs(settlement()).andExpect(status().isCreated());
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/documents/SETTLEMENT/verify")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isConflict());
  }

  @Test
  void completeEnforcesConsentAndPendingOrRevisionOnly() throws Exception {
    sendDocs(Map.of("type", "EXIT_FORMALITIES", "hrValues", Map.of())).andExpect(status().isCreated());
    complete("EXIT_FORMALITIES", Map.of("consentAccepted", false, "signatureDataUrl", PNG_1X1))
        .andExpect(status().isBadRequest());
    complete("EXIT_FORMALITIES", Map.of("consentAccepted", true, "signatureDataUrl", PNG_1X1))
        .andExpect(status().isOk());
    // After submit, HR verifies -> completing a VERIFIED doc is rejected.
    mvc.perform(post("/employees/" + emp.getId() + "/offboarding/documents/EXIT_FORMALITIES/verify")
            .header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isOk());
    complete("EXIT_FORMALITIES", Map.of("consentAccepted", true, "signatureDataUrl", PNG_1X1))
        .andExpect(status().isConflict());
  }

  // --- clearance: HR-only ---------------------------------------------------

  @Test
  void clearanceIsHrOnlyUpsertAndRegeneratesPdf() throws Exception {
    // The employee can never reach the HR clearance endpoint (there is no /me clearance route at all).
    mvc.perform(get("/employees/" + emp.getId() + "/offboarding/clearance").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isForbidden());
    mvc.perform(put("/employees/" + emp.getId() + "/offboarding/clearance")
            .header("Authorization", "Bearer " + empToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isForbidden());

    // HR fills it -> upserts + regenerates the PDF + persists the final status.
    var body =
        Map.of(
            "items",
                Map.of(
                    "id_card_returned", Map.of("value", "YES", "remarks", "returned"),
                    "laptop", Map.of("value", "YES", "remarks", "")),
            "finalItSignoff", "YES",
            "finalStatus", "APPROVED");
    mvc.perform(put("/employees/" + emp.getId() + "/offboarding/clearance")
            .header("Authorization", "Bearer " + hr1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)))
        .andExpect(status().isOk());
    assertThat(auditLogs.findByAction("OFFBOARDING_CLEARANCE_SAVED")).hasSize(1);

    JsonNode view =
        json.readTree(
            mvc.perform(get("/employees/" + emp.getId() + "/offboarding/clearance").header("Authorization", "Bearer " + hr1Token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(view.get("finalStatus").asText()).isEqualTo("APPROVED");
    assertThat(view.get("details").get("name").asText()).isEqualTo("Meera Nair");
    assertThat(view.get("sections")).isNotEmpty();
    assertThat(view.get("downloadUrl").asText()).startsWith("http");
  }

  // --- substitution across companies ----------------------------------------

  @Test
  void settlementCarriesEachCompanysNameAndSendingHr() throws Exception {
    sendDocs(settlement()).andExpect(status().isCreated());
    complete(
            "SETTLEMENT",
            Map.of(
                "consentAccepted", true,
                "fillValues", Map.of("FATHER_NAME", "F", "AGE", "30", "ADDRESS", "A"),
                "signatureDataUrl", PNG_1X1))
        .andExpect(status().isOk());
    ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
    verify(storage, atLeastOnce()).putObject(anyString(), pdf.capture(), anyString());
    String t = text(pdf.getValue());
    assertThat(t).contains("Globex Corporation");
    assertThat(t).contains("Asha Rao").doesNotContain("Kiran Thakur");
  }

  // --- helpers --------------------------------------------------------------

  private static String text(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  private Map<String, Object> settlement() {
    return Map.of(
        "type", "SETTLEMENT",
        "hrValues",
            Map.of(
                "AGREEMENT_DATE", "01/07/2026",
                "EMPLOYMENT_START", "01/08/2020",
                "LAST_DATE", "30/09/2026",
                "DESIGNATION", "Engineer",
                "SETTLEMENT_TERMS_LINE", "Settled in full."));
  }

  @SafeVarargs
  private org.springframework.test.web.servlet.ResultActions sendDocs(Map<String, Object>... selections)
      throws Exception {
    return mvc.perform(post("/employees/" + emp.getId() + "/offboarding/documents/send")
        .header("Authorization", "Bearer " + hr1Token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("documents", List.of(selections)))));
  }

  private org.springframework.test.web.servlet.ResultActions complete(String type, Map<String, Object> body)
      throws Exception {
    return mvc.perform(post("/me/offboarding/" + type + "/complete")
        .header("Authorization", "Bearer " + empToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body)));
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
    return usersSave(u);
  }

  @Autowired com.ihrms.domain.repository.UserRepository userRepo;

  private User usersSave(User u) {
    return userRepo.save(u);
  }

  @Autowired com.ihrms.domain.repository.TeamRepository teamRepo;

  private void team(String companyId, String hrUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    teamRepo.save(t);
  }

  private Employee approvedEmployee(String hrId, String code) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName("Meera Nair");
    e.setEmail("meera@personal.test");
    e.setDesignation("Engineer");
    e.setDateOfJoining(java.time.LocalDate.parse("2020-08-01"));
    e.setCompanyId(companyA);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    employees.save(e);
    Form1Personal f1 = new Form1Personal();
    f1.setEmployeeId(e.getId());
    f1.setData(Map.of("name", "Meera Nair", "currentAddress", "12 Marine Drive", "dateOfBirth", "1994-05-05"));
    f1.setStatus(SectionStatus.SUBMITTED);
    form1s.save(f1);
    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(e.getId());
    f2.setData(Map.of("fullName", "Meera Nair", "designation", "Senior Engineer"));
    f2.setStatus(SectionStatus.SUBMITTED);
    form2s.save(f2);
    return e;
  }

  private OffboardingCase offboardingCase(String employeeId, String hrId, OffboardingStatus status) {
    OffboardingCase c = new OffboardingCase();
    c.setEmployeeId(employeeId);
    c.setStatus(status);
    c.setReason("Resignation");
    c.setLastWorkingDay(java.time.LocalDate.parse("2026-09-30"));
    c.setInitiatedByUserId(hrId);
    return cases.save(c);
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), UserRole.HR, u.getCompanyId(), null));
  }
}
