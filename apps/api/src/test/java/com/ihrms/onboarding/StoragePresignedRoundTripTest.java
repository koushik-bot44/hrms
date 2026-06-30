package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.support.Hashing;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Real presigned round-trip against local MinIO (gated on {@code IHRMS_TEST_S3} so {@code ./mvnw
 * package} stays green without S3): request a presigned PUT, upload the bytes DIRECTLY to storage
 * with the returned headers (the SAME handshake the frontend uses), confirm so the server hashes the
 * stored object, and download via the presigned GET — asserting the server sha256 equals the local
 * sha256 and the round-tripped bytes are identical.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_S3", matches = ".+")
class StoragePresignedRoundTripTest {

  private static final byte[] PDF =
      "%PDF-1.4 ihrms onboarding round-trip test payload".getBytes(StandardCharsets.UTF_8);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired JdbcTemplate jdbc;

  private String token;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    Company c = new Company();
    c.setName("Acme Inc");
    c.setCode("ACME");
    companies.save(c);
    User hr = new User();
    hr.setEmail("hr@acme.test");
    hr.setName("HR");
    hr.setRole(UserRole.HR);
    hr.setCompanyId(c.getId());
    users.save(hr);
    Employee e = new Employee();
    e.setEmployeeCode("ACME-EMP-000001");
    e.setEmail("alex@personal.test");
    e.setCompanyId(c.getId());
    e.setOnboardingHrId(hr.getId());
    employees.save(e);
    token =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(e.getId(), e.getEmployeeCode(), e.getEmail(), c.getId()));
  }

  @Test
  void uploadsViaPresignedPutThenConfirmsHashAndDownloads() throws Exception {
    HttpClient http = HttpClient.newHttpClient();

    // 1) Request the presigned PUT.
    MvcResult req =
        mvc.perform(
                post("/me/onboarding/documents")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "sectionKey", "GOVERNMENT",
                                "docType", "PAN",
                                "fileName", "pan.pdf",
                                "mimeType", "application/pdf",
                                "sizeBytes", PDF.length))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode presign = json.readTree(req.getResponse().getContentAsString());
    String documentId = presign.get("documentId").asText();
    String uploadUrl = presign.get("uploadUrl").asText();

    // 2) PUT the bytes DIRECTLY to storage with the returned headers (frontend handshake).
    HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(uploadUrl));
    presign.get("headers").fields().forEachRemaining(h -> put.header(h.getKey(), h.getValue().asText()));
    HttpResponse<Void> putResp =
        http.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(PDF)).build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(putResp.statusCode()).isBetween(200, 299);

    // 3) Confirm -> server reads the stored object and hashes it.
    MvcResult confirm =
        mvc.perform(
                post("/me/onboarding/documents/" + documentId + "/confirm")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode doc = json.readTree(confirm.getResponse().getContentAsString());
    assertThat(doc.get("status").asText()).isEqualTo("UPLOADED");
    assertThat(doc.get("sha256").asText()).isEqualTo(Hashing.sha256Hex(PDF));

    // 4) Download via the presigned GET -> identical bytes.
    MvcResult view =
        mvc.perform(
                get("/me/onboarding/documents/" + documentId + "/url")
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn();
    String viewUrl = json.readTree(view.getResponse().getContentAsString()).get("url").asText();
    HttpResponse<byte[]> getResp =
        http.send(HttpRequest.newBuilder(URI.create(viewUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(getResp.statusCode()).isEqualTo(200);
    assertThat(getResp.body()).isEqualTo(PDF);
  }
}
