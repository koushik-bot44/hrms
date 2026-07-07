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
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
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
 * Employee onboarding — the four-form stepper (§3.2) — with storage MOCKED so it runs in
 * {@code ./mvnw package} with only a DB: per-form save + status flips, the document
 * request/confirm/view orchestration (sha256 from the stubbed bytes), own-record scope, the per-slot
 * cap, the signature capture, and the submission gate → the five generated PDFs → record lock.
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
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyId;
  private String hrId;
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

    // Fill Form 1 + Form 2, upload+confirm Aadhaar & PAN, capture a signature.
    saveForm1();
    saveForm2();
    confirm(upload("AADHAAR"));
    confirm(upload("PAN"));
    signature();

    MvcResult submit =
        mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode dash = json.readTree(submit.getResponse().getContentAsString());
    assertThat(dash.get("status").asText()).isEqualTo("SUBMITTED");
    // The PDFs (one per form + a merged complete application) are generated just after the submit
    // commits, so the dashboard reflects them on the next read.
    assertThat(dashboard().get("generatedDocuments")).hasSize(5);

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

  // --- helpers --------------------------------------------------------------

  private Map<String, Object> form1Body() {
    return Map.of(
        "name", "Alex Doe",
        "dateOfBirth", "1990-01-01",
        "email", "alex@personal.test",
        "mobile", "5551234",
        "designation", "Engineer",
        "offeredCtc", "1200000",
        "city", "Metro",
        "characterReferences",
            List.of(
                Map.of("name", "Ref One", "phone", "5550001"),
                Map.of("name", "Ref Two", "phone", "5550002")));
  }

  private void saveForm1() throws Exception {
    mvc.perform(
            put("/me/onboarding/form1")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(form1Body())))
        .andExpect(status().isOk());
  }

  private void saveForm2() throws Exception {
    mvc.perform(
            put("/me/onboarding/form2")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("fullName", "Alex Doe", "designation", "Engineer",
                            "panNumber", "ABCDE1234F"))))
        .andExpect(status().isOk());
  }

  private void signature() throws Exception {
    mvc.perform(
            put("/me/onboarding/signature")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("imageDataUrl", SIGNATURE, "type", "DRAWN"))))
        .andExpect(status().isOk());
  }

  private MvcResult requestUpload(String docType, Integer groupIndex, String fileName) throws Exception {
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
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)))
        .andExpect(status().isCreated())
        .andReturn();
  }

  private String upload(String docType) throws Exception {
    return json.readTree(requestUpload(docType, null, docType.toLowerCase() + ".pdf").getResponse().getContentAsString())
        .get("documentId")
        .asText();
  }

  private void confirm(String documentId) throws Exception {
    mvc.perform(
            post("/me/onboarding/documents/" + documentId + "/confirm")
                .header("Authorization", "Bearer " + empToken))
        .andExpect(status().isCreated());
  }

  private JsonNode dashboard() throws Exception {
    MvcResult res =
        mvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + empToken))
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
    return employees.save(e);
  }

  private String tokenFor(Employee e) {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee(e.getId(), e.getEmployeeCode(), e.getEmail(), companyId));
  }
}
