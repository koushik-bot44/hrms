package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Employee onboarding (contract §3.6) with storage MOCKED, so it runs in {@code ./mvnw package}
 * with only a DB: section validation + status flips, the document request/confirm/view orchestration
 * (sha256 from the stubbed bytes), own-record scope, the submission gate + lock, and audit. The real
 * presigned round-trip against MinIO lives in {@link StoragePresignedRoundTripTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OnboardingApiTest {

  private static final byte[] FILE_BYTES = "hello onboarding world".getBytes(StandardCharsets.UTF_8);
  private static final String EXPECTED_SHA = Hashing.sha256Hex(FILE_BYTES);

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
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyId = company("ACME");
    hrId = hrUser(companyId, "hr@acme.test");
    Employee emp = employee("ACME-EMP-000001", "alex@personal.test");
    empToken = tokenFor(emp);

    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("companies/c/employees/e/GOVERNMENT/obj");
    when(storage.presignedPutUrl(anyString(), anyString(), anyInt()))
        .thenReturn("http://storage.local/put?sig=test");
    when(storage.presignedGetUrl(anyString(), anyInt()))
        .thenReturn("http://storage.local/get?sig=test");
    when(storage.getObjectBytes(any())).thenReturn(FILE_BYTES);
  }

  @Test
  void savesSectionsValidatesAndFlipsToInProgress() throws Exception {
    // Valid PERSONAL -> DRAFT, employee flips INVITED -> IN_PROGRESS.
    MvcResult res =
        mvc.perform(
                putSection(
                    "PERSONAL",
                    Map.of(
                        "fullName", "Alex Doe",
                        "dateOfBirth", "1990-01-01",
                        "phone", "5551234",
                        "addressLine", "1 Main Street",
                        "city", "Metro")))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode section = json.readTree(res.getResponse().getContentAsString());
    assertThat(section.get("key").asText()).isEqualTo("PERSONAL");
    assertThat(section.get("status").asText()).isEqualTo("DRAFT");
    assertThat(section.get("data").get("fullName").asText()).isEqualTo("Alex Doe");

    assertThat(dashboard().get("status").asText()).isEqualTo("IN_PROGRESS");

    // GOVERNMENT normalizes the PAN to upper-case.
    MvcResult gov =
        mvc.perform(putSection("GOVERNMENT", Map.of("panNumber", "abcde1234f")))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(gov.getResponse().getContentAsString()).get("data").get("panNumber").asText())
        .isEqualTo("ABCDE1234F");

    // Invalid PERSONAL -> 400 with a message array.
    MvcResult bad =
        mvc.perform(putSection("PERSONAL", Map.of("dateOfBirth", "nope")))
            .andExpect(status().isBadRequest())
            .andReturn();
    assertThat(json.readTree(bad.getResponse().getContentAsString()).get("message").isArray()).isTrue();

    assertThat(auditLogs.findByAction("SECTION_SAVED")).hasSize(2);
  }

  @Test
  void documentRequestConfirmAndViewNeverExposeStorageKey() throws Exception {
    MvcResult req =
        mvc.perform(
                post("/me/onboarding/documents")
                    .header("Authorization", "Bearer " + empToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "sectionKey", "GOVERNMENT",
                                "docType", "PAN",
                                "fileName", "pan.pdf",
                                "mimeType", "application/pdf",
                                "sizeBytes", 2048))))
            .andExpect(status().isCreated())
            .andReturn();
    String reqBody = req.getResponse().getContentAsString();
    assertThat(reqBody).doesNotContain("storageKey").doesNotContain("companies/");
    JsonNode presign = json.readTree(reqBody);
    assertThat(presign.get("method").asText()).isEqualTo("PUT");
    assertThat(presign.get("headers").get("Content-Type").asText()).isEqualTo("application/pdf");
    assertThat(presign.get("expiresInSeconds").asInt()).isEqualTo(300);
    String documentId = presign.get("documentId").asText();

    // Confirm -> server hashes the (stubbed) bytes and marks UPLOADED.
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

    // View URL -> short-lived presigned GET, audited.
    MvcResult view =
        mvc.perform(
                get("/me/onboarding/documents/" + documentId + "/url")
                    .header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode v = json.readTree(view.getResponse().getContentAsString());
    assertThat(v.get("url").asText()).startsWith("http");
    assertThat(v.get("expiresInSeconds").asInt()).isEqualTo(120);

    assertThat(auditLogs.findByAction("DOCUMENT_UPLOAD_REQUESTED")).hasSize(1);
    assertThat(auditLogs.findByAction("DOCUMENT_UPLOADED")).hasSize(1);
    assertThat(auditLogs.findByAction("DOCUMENT_VIEWED")).hasSize(1);
  }

  @Test
  void anotherEmployeeCannotTouchMyDocument() throws Exception {
    String documentId = uploadPan(empToken);

    Employee other = employee("ACME-EMP-000002", "sam@personal.test");
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
  void submitIsGatedThenLocksTheRecord() throws Exception {
    // Only PERSONAL saved -> still missing GOVERNMENT + the PAN document.
    mvc.perform(
            putSection(
                "PERSONAL",
                Map.of(
                    "fullName", "Alex Doe",
                    "dateOfBirth", "1990-01-01",
                    "phone", "5551234",
                    "addressLine", "1 Main Street",
                    "city", "Metro")))
        .andExpect(status().isOk());
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isBadRequest());

    // Complete GOVERNMENT + an UPLOADED PAN, then submit succeeds.
    mvc.perform(putSection("GOVERNMENT", Map.of("panNumber", "ABCDE1234F"))).andExpect(status().isOk());
    String documentId = uploadPan(empToken);
    mvc.perform(
            post("/me/onboarding/documents/" + documentId + "/confirm")
                .header("Authorization", "Bearer " + empToken))
        .andExpect(status().isCreated());

    MvcResult submit =
        mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(json.readTree(submit.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("SUBMITTED");

    // Locked: further edits and re-submit are rejected.
    mvc.perform(putSection("PERSONAL", Map.of("fullName", "Changed Name", "dateOfBirth", "1990-01-01",
            "phone", "5551234", "addressLine", "1 Main Street", "city", "Metro")))
        .andExpect(status().isConflict());
    mvc.perform(post("/me/onboarding/submit").header("Authorization", "Bearer " + empToken))
        .andExpect(status().isConflict());

    assertThat(auditLogs.findByAction("EMPLOYEE_SUBMITTED")).hasSize(1);
  }

  @Test
  void rejectsBadUploadRequests() throws Exception {
    mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("sectionKey", "GOVERNMENT", "docType", "PAN", "fileName", "x.txt",
                            "mimeType", "text/plain", "sizeBytes", 10))))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/me/onboarding/documents")
                .header("Authorization", "Bearer " + empToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("sectionKey", "GOVERNMENT", "docType", "PAN", "fileName", "big.pdf",
                            "mimeType", "application/pdf", "sizeBytes", 20L * 1024 * 1024))))
        .andExpect(status().isBadRequest());
  }

  // --- helpers --------------------------------------------------------------

  private String uploadPan(String token) throws Exception {
    MvcResult req =
        mvc.perform(
                post("/me/onboarding/documents")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("sectionKey", "GOVERNMENT", "docType", "PAN", "fileName", "pan.pdf",
                                "mimeType", "application/pdf", "sizeBytes", 2048))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(req.getResponse().getContentAsString()).get("documentId").asText();
  }

  private JsonNode dashboard() throws Exception {
    MvcResult res =
        mvc.perform(get("/me/onboarding").header("Authorization", "Bearer " + empToken))
            .andExpect(status().isOk())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private MockHttpServletRequestBuilder putSection(String key, Map<String, Object> data)
      throws Exception {
    return put("/me/onboarding/sections/" + key)
        .header("Authorization", "Bearer " + empToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("data", data)));
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

  private Employee employee(String code, String email) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
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
