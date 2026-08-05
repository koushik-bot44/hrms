package com.ihrms.agreement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeAgreementRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
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
 * Post-approval agreements (§Agreements) end to end over a real DB: HR sends the pack (APPROVED-only,
 * idempotent, own-scope), the employee reads the substituted text and completes each agreement (consent +
 * Aadhaar validation + signature), a PDF is rendered and stored, Aadhaar is encrypted at rest / masked /
 * audited-revealed like PAN, and the HR record surfaces the agreement statuses + downloads.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AgreementApiTest {

  private static final String PNG_1X1 =
      "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired Form1PersonalRepository form1s;
  @Autowired Form2InfoRepository form2s;
  @Autowired EmployeeAgreementRepository agreements;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hr1;
  private String hr1Token;
  private Employee emp; // APPROVED, onboarded by hr1, code minted, form1 + form2 present
  private String empToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\","
            + "\"employee_agreements\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("Globex Corporation", "GLBX");
    hr1 = user(companyA, UserRole.HR, "asha.hr@globex.test", "Asha Rao");
    team(companyA, hr1.getId());
    hr1Token = tokenFor(hr1);
    emp = approvedEmployee(hr1.getId(), "GLBX-EMP-000001");
    empToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(emp.getId(), emp.getEmployeeCode(), emp.getEmail(), companyA));

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://storage.local/get?sig=test");
    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(
            inv ->
                "companies/"
                    + inv.getArgument(0)
                    + "/employees/"
                    + inv.getArgument(1)
                    + "/"
                    + inv.getArgument(2)
                    + "/"
                    + inv.getArgument(3));
  }

  // --- send -----------------------------------------------------------------

  @Test
  void hrSendsPackCreatingThreePendingRowsAndAuditsIt() throws Exception {
    var res = send().andExpect(status().isCreated()).andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("agreements")).hasSize(3);
    assertThat(body.get("agreements").get(0).get("status").asText()).isEqualTo("PENDING");
    assertThat(body.get("agreements").get(0).get("sentByName").asText()).isEqualTo("Asha Rao");

    assertThat(agreements.findByEmployeeId(emp.getId())).hasSize(3);
    assertThat(agreements.findByEmployeeId(emp.getId()))
        .allSatisfy(
            a -> {
              assertThat(a.getStatus().name()).isEqualTo("PENDING");
              assertThat(a.getSentByUserId()).isEqualTo(hr1.getId());
              assertThat(a.getStorageKey()).isNull();
            });
    assertThat(auditLogs.findByAction("AGREEMENTS_SENT")).hasSize(1);
  }

  @Test
  void sendIsRejectedUnlessEmployeeApproved() throws Exception {
    Employee submitted = new Employee();
    submitted.setFullName("Not Approved");
    submitted.setEmail("na@globex.test");
    submitted.setCompanyId(companyA);
    submitted.setOnboardingHrId(hr1.getId());
    submitted.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(submitted);

    mvc.perform(post("/employees/" + submitted.getId() + "/agreements/send").header("Authorization", "Bearer " + hr1Token))
        .andExpect(status().isConflict());
    assertThat(agreements.findByEmployeeId(submitted.getId())).isEmpty();
  }

  @Test
  void sendIsIdempotent() throws Exception {
    send().andExpect(status().isCreated());
    send().andExpect(status().isConflict()); // second send rejected
    assertThat(agreements.findByEmployeeId(emp.getId())).hasSize(3); // still only one pack
  }

  @Test
  void riderAPartialSendThenRemainderThenFullDuplicate() throws Exception {
    // Send only the AUP now.
    sendTypes(emp.getId(), hr1Token, "AUP").andExpect(status().isCreated());
    assertThat(agreements.findByEmployeeId(emp.getId())).hasSize(1);
    assertThat(agreements.findByEmployeeIdAndType(emp.getId(), com.ihrms.domain.enums.EmployeeAgreementType.AUP))
        .isPresent();

    // Re-selecting a mix where AUP already exists creates ONLY the missing ones (NDA, Notice), not a 409.
    sendTypes(emp.getId(), hr1Token, "AUP", "NDA", "NOTICE_PERIOD").andExpect(status().isCreated());
    assertThat(agreements.findByEmployeeId(emp.getId())).hasSize(3); // no duplicate AUP

    // Now every selected type exists -> a full duplicate is a 409.
    sendTypes(emp.getId(), hr1Token, "AUP", "NDA").andExpect(status().isConflict());
    assertThat(agreements.findByEmployeeId(emp.getId())).hasSize(3);

    // Empty selection is a 400.
    sendTypes(emp.getId(), hr1Token).andExpect(status().isBadRequest());
  }

  @Test
  void sendScopedToTheOnboardingHr() throws Exception {
    String hr2 = tokenFor(user(companyA, UserRole.HR, "other.hr@globex.test", "Other HR"));
    mvc.perform(post("/employees/" + emp.getId() + "/agreements/send").header("Authorization", "Bearer " + hr2))
        .andExpect(status().isNotFound());
    String companyB = company("Beta Labs", "BETA");
    String hr3 = tokenFor(user(companyB, UserRole.HR, "hr@beta.test", "Beta HR"));
    mvc.perform(post("/employees/" + emp.getId() + "/agreements/send").header("Authorization", "Bearer " + hr3))
        .andExpect(status().isNotFound());
    assertThat(agreements.findByEmployeeId(emp.getId())).isEmpty();
  }

  // --- employee read + complete ---------------------------------------------

  @Test
  void employeeReadsAgreementWithCompanyNameSubstitutedAndPrefills() throws Exception {
    send().andExpect(status().isCreated());

    JsonNode list =
        json.readTree(
            mvc.perform(get("/me/agreements").header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(list).hasSize(3);

    JsonNode nda =
        json.readTree(
            mvc.perform(get("/me/agreements/NDA").header("Authorization", "Bearer " + empToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(nda.get("status").asText()).isEqualTo("PENDING");
    String bodyHtml = nda.get("bodyHtml").asText();
    assertThat(bodyHtml).contains("Globex Corporation");
    assertThat(bodyHtml).doesNotContain("SCREATIVES");
    assertThat(nda.get("prefill").get("designation").asText()).isEqualTo("Senior Engineer");
    assertThat(nda.get("prefill").get("address").asText()).isEqualTo("12 Marine Drive, Mumbai");
  }

  @Test
  void employeeCompletesAupRendersPdfEncryptsAadhaarAndNotifiesHr() throws Exception {
    send().andExpect(status().isCreated());

    var res =
        complete("AUP", Map.of("consentAccepted", true, "aadhaar", "1234 5678 9012", "signatureDataUrl", PNG_1X1))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(body.get("downloadUrl").asText()).startsWith("http");

    var saved = agreements.findByEmployeeIdAndType(emp.getId(), com.ihrms.domain.enums.EmployeeAgreementType.AUP).orElseThrow();
    assertThat(saved.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(saved.getStorageKey()).endsWith("/agreements/aup.pdf");
    assertThat(saved.getCompletedAt()).isNotNull();

    // The rendered PDF stamps the company name and the full Aadhaar.
    ArgumentCaptor<byte[]> pdf = ArgumentCaptor.forClass(byte[].class);
    verify(storage, atLeastOnce()).putObject(anyString(), pdf.capture(), anyString());
    String t = text(pdf.getValue());
    assertThat(t).contains("Globex Corporation").doesNotContain("SCREATIVES");
    assertThat(t).contains("1234 5678 9012");

    // Aadhaar is ENCRYPTED at rest (ciphertext != the digits) but the digits round-trip via the entity.
    String raw = jdbc.queryForObject("SELECT \"aadhaarNumber\" FROM employees WHERE id = ?", String.class, emp.getId());
    assertThat(raw).isNotNull().isNotEqualTo("123456789012");
    assertThat(employees.findById(emp.getId()).orElseThrow().getAadhaarNumber()).isEqualTo("123456789012");

    // The sending HR gets a durable AGREEMENT_COMPLETED notification; the completion is audited.
    assertThat(notifications.findByRecipientUserId(hr1.getId()))
        .singleElement()
        .satisfies(n -> assertThat(n.getType().name()).isEqualTo("AGREEMENT_COMPLETED"));
    assertThat(auditLogs.findByAction("AGREEMENT_COMPLETED")).hasSize(1);
  }

  @Test
  void completeEnforcesConsentAadhaarSignatureAndPendingOnly() throws Exception {
    send().andExpect(status().isCreated());

    // Consent must be accepted.
    complete("AUP", Map.of("consentAccepted", false, "aadhaar", "123456789012", "signatureDataUrl", PNG_1X1))
        .andExpect(status().isBadRequest());
    // Aadhaar must be exactly 12 digits.
    complete("AUP", Map.of("consentAccepted", true, "aadhaar", "12345", "signatureDataUrl", PNG_1X1))
        .andExpect(status().isBadRequest());
    // Signature is required.
    complete("AUP", Map.of("consentAccepted", true, "aadhaar", "123456789012"))
        .andExpect(status().isBadRequest());

    // A valid completion, then a second attempt on the same agreement is rejected (409).
    complete("AUP", Map.of("consentAccepted", true, "aadhaar", "123456789012", "signatureDataUrl", PNG_1X1))
        .andExpect(status().isOk());
    complete("AUP", Map.of("consentAccepted", true, "aadhaar", "123456789012", "signatureDataUrl", PNG_1X1))
        .andExpect(status().isConflict());
  }

  @Test
  void completingAnUnsentAgreementIs404() throws Exception {
    // No pack sent yet.
    complete("NOTICE_PERIOD", Map.of("consentAccepted", true, "signatureDataUrl", PNG_1X1))
        .andExpect(status().isNotFound());
  }

  // --- record view: masked aadhaar + agreements + audited reveal ------------

  @Test
  void recordMasksAadhaarListsAgreementsAndRevealIsAudited() throws Exception {
    send().andExpect(status().isCreated());
    complete("AUP", Map.of("consentAccepted", true, "aadhaar", "123456789012", "signatureDataUrl", PNG_1X1))
        .andExpect(status().isOk());

    JsonNode record =
        json.readTree(
            mvc.perform(get("/employees/" + emp.getId() + "/record").header("Authorization", "Bearer " + hr1Token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(record.get("aadhaarNumber").asText()).isEqualTo("********"); // masked (§6)
    assertThat(record.get("agreements")).hasSize(3);
    JsonNode aup =
        java.util.stream.StreamSupport.stream(record.get("agreements").spliterator(), false)
            .filter(a -> a.get("type").asText().equals("AUP"))
            .findFirst()
            .orElseThrow();
    assertThat(aup.get("status").asText()).isEqualTo("COMPLETED");
    assertThat(aup.get("downloadUrl").asText()).startsWith("http");

    JsonNode revealed =
        json.readTree(
            mvc.perform(post("/employees/" + emp.getId() + "/reveal").header("Authorization", "Bearer " + hr1Token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(revealed.get("aadhaarNumber").asText()).isEqualTo("123456789012"); // plaintext on reveal
    assertThat(auditLogs.findByAction("SENSITIVE_FIELD_REVEALED")).isNotEmpty();
  }

  // --- helpers --------------------------------------------------------------

  private static String text(byte[] pdf) throws Exception {
    try (PDDocument doc = PDDocument.load(pdf)) {
      return new PDFTextStripper().getText(doc);
    }
  }

  /** Send the full standard pack (all three types). */
  private org.springframework.test.web.servlet.ResultActions send() throws Exception {
    return sendTypes(emp.getId(), hr1Token, "AUP", "NDA", "NOTICE_PERIOD");
  }

  /** Send a chosen subset (Rider A). */
  private org.springframework.test.web.servlet.ResultActions sendTypes(
      String employeeId, String token, String... types) throws Exception {
    return mvc.perform(post("/employees/" + employeeId + "/agreements/send")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("types", java.util.List.of(types)))));
  }

  private org.springframework.test.web.servlet.ResultActions complete(String type, Map<String, Object> payload)
      throws Exception {
    return mvc.perform(post("/me/agreements/" + type + "/complete")
        .header("Authorization", "Bearer " + empToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(payload)));
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

  private void team(String companyId, String hrUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
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
    employees.save(e);

    Form1Personal f1 = new Form1Personal();
    f1.setEmployeeId(e.getId());
    f1.setData(Map.of("name", "Meera Nair", "mobile", "9812345678", "currentAddress", "12 Marine Drive, Mumbai"));
    f1.setStatus(SectionStatus.SUBMITTED);
    form1s.save(f1);

    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(e.getId());
    f2.setData(Map.of("fullName", "Meera Nair", "designation", "Senior Engineer"));
    f2.setStatus(SectionStatus.SUBMITTED);
    form2s.save(f2);
    return e;
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), UserRole.HR, u.getCompanyId(), null));
  }
}
