package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Employee onboarding — the employee stepper for Forms 1/3/4 (§3.2; Form 2 is HR-authored at onboard,
 * seeded here) — with storage MOCKED so it runs in {@code ./mvnw package} with only a DB: per-form save
 * + status flips, the document request/confirm/view orchestration (sha256 from the stubbed bytes),
 * own-record scope, the per-slot cap, the signature capture, and the submission gate → the generated
 * PDFs → record lock. Also asserts the employee never sees Form 2 (no dashboard view, no PDF).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OnboardingApiTest {

  private static final byte[] FILE_BYTES = "hello onboarding world".getBytes(StandardCharsets.UTF_8);
  private static final String EXPECTED_SHA = Hashing.sha256Hex(FILE_BYTES);
  private static final String SIGNATURE = "data:image/png;base64,aGVsbG8gc2lnbmF0dXJl";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired Form1PersonalRepository form1s;
  @Autowired com.ihrms.domain.repository.Form2InfoRepository form2s;
  @Autowired GeneratedDocumentRepository generated;
  @Autowired DocumentRepository documents;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyId;
  private String hrId;
  private String empId;
  private String empToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyId = company("ACME");
    hrId = hrUser(companyId, "hr@acme.test");
    Employee emp = employee("alex@personal.test");
    empId = emp.getId();
    empToken = tokenFor(emp);

    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("companies/c/employees/e/form4/obj");
    when(storage.presignedPutUrl(anyString(), anyString(), anyInt()))
        .thenReturn("http://storage.local/put?sig=test");
    when(storage.presignedGetUrl(anyString(), anyInt()))
        .thenReturn("http://storage.local/get?sig=test");
    when(storage.getObjectBytes(any())).thenReturn(FILE_BYTES);
  }

  @Test
  void savesForm1FlipsToInProgress() throws Exception {
    MvcResult res =
        mvc.perform(
                put("/me/onboarding/form1")
                    .header("Authorization", "Bearer " + empToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(form1Body())))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode form1 = json.readTree(res.getResponse().getContentAsString());
    assertThat(form1.get("name").asText()).isEqualTo("Alex Doe");
    assertThat(form1.get("status").asText()).isEqualTo("DRAFT");
    assertThat(form1.get("characterReferences")).hasSize(2);
    // Relocated fields (§3.2): the OWN view echoes them PLAIN, and they persist to their UNCHANGED
    // form2_info storage (PAN in the encrypted column, alternate number in the data JSON).
    assertThat(form1.get("panNumber").asText()).isEqualTo("ABCDE1234F");
    assertThat(form1.get("alternateNumber").asText()).isEqualTo("5559999");
    var f2Row = form2s.findByEmployeeId(empId).orElseThrow();
    assertThat(f2Row.getPanNumber()).isEqualTo("ABCDE1234F"); // decrypted by the converter
    assertThat(f2Row.getData().get("alternateNumber")).isEqualTo("5559999");
    // Form 1's write-through coexists with the HR-authored Form 2 on the one shared row: the employment
    // data (fullName) is preserved alongside the relocated PAN/alt values (no clobber, §3.2).
    assertThat(f2Row.getData().get("fullName")).isEqualTo("Alex Doe");
    // The raw column holds ciphertext, not the plaintext PAN (encryption at rest preserved).
    String rawPan = jdbc.queryForObject(
        "SELECT \"panNumber\" FROM \"form2_info\" WHERE \"employeeId\" = ?", String.class, empId);
    assertThat(rawPan).isNotBlank().isNotEqualTo("ABCDE1234F");

    assertThat(dashboard().get("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(auditLogs.findByAction("FORM1_SAVED")).hasSize(1);
  }

  @Test
  void documentRequestConfirmAndViewNeverExposeStorageKey() throws Exception {
    MvcResult req = requestUpload("PAN", null, "pan.pdf");
    String reqBody = req.getResponse().getContentAsString();
    assertThat(reqBody).doesNotContain("storageKey").doesNotContain("companies/");
    JsonNode presign = json.readTree(reqBody);
    assertThat(presign.get("method").asText()).isEqualTo("PUT");
    assertThat(presign.get("headers").get("Content-Type").asText()).isEqualTo("application/pdf");
    assertThat(presign.get("expiresInSeconds").asInt()).isEqualTo(300);
    String documentId = presign.get("documentId").asText();

    MvcResult confirm =
        mvc.perform(
                post("/me/onboarding/documents/" + documentId + "/confirm")
                    .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode doc = json.readTree(confirm.getResponse().getContentAsString());
    assertThat(doc.get("status").asText()).isEqualTo("UPLOADED");
    assertThat(doc.get("sha256").asText()).isEqualTo(EXPECTED_SHA);
    assertThat(confirm.getResponse().getContentAsString()).doesNotContain("storageKey");

    MvcResult view =
        mvc.perform(
                get("/me/onboarding/documents/" + documentId + "/url")
                    .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode v = json.readTree(view.getResponse().getContentAsString());
    assertThat(v.get("url").asText()).startsWith("http");
    assertThat(v.get("expiresInSeconds").asInt()).isEqualTo(60);

    assertThat(auditLogs.findByAction("DOCUMENT_UPLOAD_REQUESTED")).hasSize(1);
    assertThat(auditLogs.findByAction("DOCUMENT_UPLOADED")).hasSize(1);
    assertThat(auditLogs.findByAction("DOCUMENT_VIEWED")).hasSize(1);
  }

  @Test
  void anotherEmployeeCannotTouchMyDocument() throws Exception {
    String documentId = upload("PAN");

    Employee other = employee("sam@personal.test");
    String otherToken = tokenFor(other);

    mvc.perform(
            post("/me/onboarding/documents/" + documentId + "/confirm")
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/me/onboarding/documents/" + documentId + "/url")
                .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());
  }

  @Test
  void submitIsGatedThenGeneratesPdfsAndLocksTheRecord() throws Exception {
    // Nothing filled -> gated.
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isBadRequest());

    // Fill Form 1 (Form 2 is HR-authored, already seeded), upload+confirm Aadhaar & PAN, capture a sig.
    saveForm1();
    confirm(upload("AADHAAR"));
    confirm(upload("PAN"));
    signature();

    MvcResult submit =
        mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode dash = json.readTree(submit.getResponse().getContentAsString());
    assertThat(dash.get("status").asText()).isEqualTo("SUBMITTED");
    // All FIVE PDFs are generated post-commit (FORM1/FORM2/FORM3/FORM4_MANIFEST/MERGED)...
    assertThat(generated.findByEmployeeId(empId)).hasSize(5);
    // ...but the employee's dashboard OMITS the HR-only Form 2 PDF -> only 4 are visible (§3.2).
    JsonNode gen = dashboard().get("generatedDocuments");
    assertThat(gen).hasSize(4);
    assertThat(gen).allSatisfy(g -> assertThat(g.get("kind").asText()).isNotEqualTo("FORM2"));

    // Even directly, the employee may not fetch the Form 2 PDF -> 403.
    String form2GenId =
        generated.findByEmployeeId(empId).stream()
            .filter(g -> g.getKind() == GeneratedDocumentKind.FORM2)
            .findFirst()
            .orElseThrow()
            .getId();
    mvc.perform(
            get("/me/onboarding/generated/" + form2GenId + "/url")
                .header("Authorization", "Bearer " + empToken))
        .andExpect(status().isForbidden());

    // Locked: further edits and re-submit are rejected.
    mvc.perform(
            put("/me/onboarding/form1")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form1Body())))
        .andExpect(status().isConflict());
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isConflict());

    assertThat(auditLogs.findByAction("EMPLOYEE_SUBMITTED")).hasSize(1);
    assertThat(auditLogs.findByAction("SIGNATURE_CAPTURED")).hasSize(1);
  }

  @Test
  void itrGatesNewOnboardsButNeverExistingEmployees() throws Exception {
    // (a) EXISTING cohort: the seeded employee carries itrRequired=false (the migration default), so
    //     Form 1 + Aadhaar + PAN + signature is enough — ITR is never demanded. No one already onboarded
    //     is retroactively gated or re-opened.
    assertThat(dashboard().get("itrRequired").asBoolean()).isFalse();
    saveForm1();
    confirm(upload("AADHAAR"));
    confirm(upload("PAN"));
    signature();
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isCreated());

    // (b) NEW onboarding: itrRequired=true (EmployeesService sets this at onboard). ITR now blocks submit.
    Employee fresh = employee("nova@personal.test");
    fresh.setItrRequired(true);
    employees.save(fresh);
    String freshToken = tokenFor(fresh);
    assertThat(dashboardFor(freshToken).get("itrRequired").asBoolean()).isTrue();

    // Form 1 + Aadhaar + PAN + signature but NO ITR -> gated on the ITR Form.
    saveForm1For(freshToken);
    confirmFor(freshToken, uploadFor(freshToken, "AADHAAR"));
    confirmFor(freshToken, uploadFor(freshToken, "PAN"));
    signatureFor(freshToken);
    MvcResult gated =
        mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + freshToken))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertThat(gated.getResponse().getContentAsString()).contains("ITR Form");

    // Upload the ITR -> the gate clears and submit succeeds.
    confirmFor(freshToken, uploadFor(freshToken, "ITR"));
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + freshToken))
        .andExpect(status().isCreated());
    assertThat(employees.findById(fresh.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.SUBMITTED);
  }

  @Test
  void rejectsBadUploadRequests() throws Exception {
    mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("docType", "PAN", "fileName", "x.txt",
                            "mimeType", "text/plain", "sizeBytes", 10))))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("docType", "PAN", "fileName", "big.pdf",
                            "mimeType", "application/pdf", "sizeBytes", 20L * 1024 * 1024))))
        .andExpect(status().isBadRequest());
  }

  @Test
  void capsDocumentsPerSlotThenDeleteFreesASlot() throws Exception {
    String first = upload("PAN"); // 1st
    upload("PAN"); // 2nd — the cap is 2

    // 3rd of the same slot is rejected.
    mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("docType", "PAN", "fileName", "extra.pdf",
                            "mimeType", "application/pdf", "sizeBytes", 2048))))
        .andExpect(status().isConflict());

    MvcResult del =
        mvc.perform(
                delete("/me/onboarding/documents/" + first)
                    .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(del.getResponse().getContentAsString()).get("documents").size())
        .isEqualTo(1);

    upload("PAN"); // allowed again
    assertThat(auditLogs.findByAction("DOCUMENT_DELETED")).hasSize(1);
  }

  @Test
  void nonIdDocumentsCapAtOneFile() throws Exception {
    upload("SECONDARY"); // the single allowed file for a non-Aadhaar/PAN slot

    // A second file for the same single-file slot is rejected.
    mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("docType", "SECONDARY", "fileName", "again.pdf",
                            "mimeType", "application/pdf", "sizeBytes", 2048))))
        .andExpect(status().isConflict());
  }

  @Test
  void revisionLoopUnlocksOnlyFlaggedItemsThenResubmitsForReReview() throws Exception {
    // Reach SUBMITTED with Form 1 + Aadhaar + PAN + signature (Form 2 is HR-authored, seeded).
    saveForm1();
    String aadhaarId = upload("AADHAAR");
    confirm(aadhaarId);
    String panId = upload("PAN");
    confirm(panId);
    signature();
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isCreated());

    // HR sends Form 1 and the PAN document back for revision (simulated at the data layer).
    Form1Personal f1 = form1s.findByEmployeeId(empId).orElseThrow();
    f1.setStatus(SectionStatus.REVISION_REQUESTED);
    f1.setRevisionNote("Your city looks wrong - please correct it");
    form1s.save(f1);
    Document pan = documents.findById(panId).orElseThrow();
    pan.setStatus(DocumentStatus.REVISION_REQUESTED);
    pan.setRevisionNote("The PAN scan is blurry");
    documents.save(pan);
    Employee e = employees.findById(empId).orElseThrow();
    e.setStatus(EmployeeStatus.REVISION_REQUESTED);
    employees.save(e);

    // The dashboard surfaces the revision state + HR's per-item notes.
    JsonNode dash = dashboard();
    assertThat(dash.get("status").asText()).isEqualTo("REVISION_REQUESTED");
    assertThat(dash.get("form1").get("revisionNote").asText())
        .isEqualTo("Your city looks wrong - please correct it");

    // The flagged form IS editable — saving it clears the note and drops it to DRAFT.
    saveForm1();
    Form1Personal savedF1 = form1s.findByEmployeeId(empId).orElseThrow();
    assertThat(savedF1.getStatus()).isEqualTo(SectionStatus.DRAFT);
    assertThat(savedF1.getRevisionNote()).isNull();

    // Re-submit is blocked while the PAN document is still flagged.
    mvc.perform(post("/me/onboarding/resubmit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isBadRequest());

    // A NON-flagged document cannot be revised…
    mvc.perform(post("/me/onboarding/documents/" + aadhaarId + "/revise")
            .header("Authorization", "Bearer " + empToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(reviseBody("aadhaar-v2.pdf"))))
        .andExpect(status().isConflict());

    // …but the flagged PAN can be replaced in place, then confirmed back to UPLOADED.
    mvc.perform(post("/me/onboarding/documents/" + panId + "/revise")
            .header("Authorization", "Bearer " + empToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(reviseBody("pan-clearer.pdf"))))
        .andExpect(status().isCreated());
    confirm(panId);
    Document revisedPan = documents.findById(panId).orElseThrow();
    assertThat(revisedPan.getStatus()).isEqualTo(DocumentStatus.UPLOADED);
    assertThat(revisedPan.getRevisionNote()).isNull();
    assertThat(revisedPan.getFileName()).isEqualTo("pan-clearer.pdf");

    // Now every flagged item is fixed → re-submit returns them to HR for re-review.
    mvc.perform(post("/me/onboarding/resubmit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isCreated());
    assertThat(employees.findById(empId).orElseThrow().getStatus()).isEqualTo(EmployeeStatus.SUBMITTED);
    assertThat(form1s.findByEmployeeId(empId).orElseThrow().getStatus()).isEqualTo(SectionStatus.SUBMITTED);
    assertThat(notifications.findByRecipientUserId(hrId))
        .anySatisfy(n -> assertThat(n.getType().name()).isEqualTo("EMPLOYEE_SUBMITTED"));
    assertThat(auditLogs.findByAction("EMPLOYEE_RESUBMITTED")).hasSize(1);
  }

  // --- helpers --------------------------------------------------------------

  private Map<String, Object> reviseBody(String fileName) {
    return Map.of("fileName", fileName, "mimeType", "application/pdf", "sizeBytes", 2048);
  }

  private Map<String, Object> form1Body() {
    // panNumber/axisAccountNumber/alternateNumber/vehicleNo2W4W are captured by Form 1 now (§3.2);
    // storage stays on form2_info (write-through).
    return Map.of(
        "name", "Alex Doe",
        "dateOfBirth", "1990-01-01",
        "email", "alex@personal.test",
        "mobile", "5551234",
        "city", "Metro",
        "panNumber", "ABCDE1234F",
        "alternateNumber", "5559999",
        "characterReferences",
            List.of(
                Map.of("name", "Ref One", "phone", "5550001"),
                Map.of("name", "Ref Two", "phone", "5550002")));
  }

  private void saveForm1() throws Exception {
    saveForm1For(empToken);
  }

  private void saveForm1For(String token) throws Exception {
    mvc.perform(
            put("/me/onboarding/form1")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form1Body())))
        .andExpect(status().isOk());
  }

  private void signature() throws Exception {
    signatureFor(empToken);
  }

  private void signatureFor(String token) throws Exception {
    mvc.perform(
            put("/me/onboarding/signature")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("imageDataUrl", SIGNATURE, "type", "DRAWN"))))
        .andExpect(status().isOk());
  }

  private MvcResult requestUpload(String docType, Integer groupIndex, String fileName) throws Exception {
    return requestUploadFor(empToken, docType, groupIndex, fileName);
  }

  private MvcResult requestUploadFor(
      String token, String docType, Integer groupIndex, String fileName) throws Exception {
    var body = new java.util.HashMap<String, Object>();
    body.put("docType", docType);
    body.put("fileName", fileName);
    body.put("mimeType", "application/pdf");
    body.put("sizeBytes", 2048);
    if (groupIndex != null) {
      body.put("groupIndex", groupIndex);
    }
    return mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private String upload(String docType) throws Exception {
    return uploadFor(empToken, docType);
  }

  private String uploadFor(String token, String docType) throws Exception {
    return json.readTree(
            requestUploadFor(token, docType, null, docType.toLowerCase() + ".pdf")
                .getResponse()
                .getContentAsString())
        .get("documentId")
        .asText();
  }

  private void confirm(String documentId) throws Exception {
    confirmFor(empToken, documentId);
  }

  private void confirmFor(String token, String documentId) throws Exception {
    mvc.perform(
            post("/me/onboarding/documents/" + documentId + "/confirm")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isCreated());
  }

  private JsonNode dashboard() throws Exception {
    return dashboardFor(empToken);
  }

  private JsonNode dashboardFor(String token) throws Exception {
    MvcResult res =
        mvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private String hrUser(String companyId, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(UserRole.HR);
    u.setCompanyId(companyId);
    return users.save(u).getId();
  }

  private Employee employee(String email) {
    Employee e = new Employee();
    e.setEmail(email);
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e = employees.save(e);
    // Form 2 is HR/SA-authored at onboard (§3.2) — seed it so the record mirrors a real onboard.
    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(e.getId());
    f2.setData(
        new java.util.HashMap<>(
            Map.of("fullName", "Alex Doe", "personalEmail", email, "designation", "Engineer")));
    form2s.save(f2);
    return e;
  }

  private String tokenFor(Employee e) {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee(e.getId(), e.getEmployeeCode(), e.getEmail(), companyId));
  }
}
