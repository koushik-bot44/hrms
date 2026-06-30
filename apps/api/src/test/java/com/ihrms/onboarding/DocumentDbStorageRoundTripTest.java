package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * End-to-end round-trip for {@code STORAGE_DRIVER=db}: with no S3 configured, request the API's own
 * "presigned" PUT, upload the bytes THROUGH the public /storage/blobs endpoint (the SAME handshake
 * the frontend uses) into Postgres, confirm so the server hashes the stored bytes, and download via
 * the view URL — asserting the server sha256 equals the local sha256 and the bytes are identical.
 * Gated on {@code IHRMS_TEST_DB} so {@code ./mvnw package} stays green without a DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "app.storage.driver=db")
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class DocumentDbStorageRoundTripTest {

  private static final byte[] PDF =
      "%PDF-1.4 ihrms db-storage round-trip payload".getBytes(StandardCharsets.UTF_8);

  @LocalServerPort int port;
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
            + "\"documents\",\"document_blobs\",\"approval_requests\",\"notifications\","
            + "\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");
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
  void uploadsThroughApiIntoPostgresThenDownloads() throws Exception {
    HttpClient http = HttpClient.newHttpClient();
    String base = "http://localhost:" + port;

    // 1) Request the API's own presigned PUT (no S3).
    HttpResponse<String> req =
        http.send(
            HttpRequest.newBuilder(URI.create(base + "/me/onboarding/documents"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        json.writeValueAsString(
                            Map.of(
                                "sectionKey", "GOVERNMENT",
                                "docType", "PAN",
                                "fileName", "pan.pdf",
                                "mimeType", "application/pdf",
                                "sizeBytes", PDF.length))))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(req.statusCode()).isEqualTo(201);
    JsonNode presign = json.readTree(req.body());
    String documentId = presign.get("documentId").asText();
    String uploadUrl = presign.get("uploadUrl").asText();
    assertThat(uploadUrl).contains("/storage/blobs/");

    // 2) PUT the bytes to the (in-app) blob endpoint with the returned headers — frontend handshake.
    HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(uploadUrl));
    presign.get("headers").fields().forEachRemaining(h -> put.header(h.getKey(), h.getValue().asText()));
    HttpResponse<Void> putResp =
        http.send(
            put.PUT(HttpRequest.BodyPublishers.ofByteArray(PDF)).build(),
            HttpResponse.BodyHandlers.discarding());
    assertThat(putResp.statusCode()).isBetween(200, 299);

    // 3) Confirm -> server reads the stored bytes from Postgres and hashes them.
    HttpResponse<String> confirm =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(base + "/me/onboarding/documents/" + documentId + "/confirm"))
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(confirm.statusCode()).isEqualTo(201);
    JsonNode doc = json.readTree(confirm.body());
    assertThat(doc.get("status").asText()).isEqualTo("UPLOADED");
    assertThat(doc.get("sha256").asText()).isEqualTo(Hashing.sha256Hex(PDF));

    // 4) Download via the view URL -> identical bytes.
    HttpResponse<String> view =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(base + "/me/onboarding/documents/" + documentId + "/url"))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(view.statusCode()).isEqualTo(200);
    String viewUrl = json.readTree(view.body()).get("url").asText();
    HttpResponse<byte[]> getResp =
        http.send(
            HttpRequest.newBuilder(URI.create(viewUrl)).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    assertThat(getResp.statusCode()).isEqualTo(200);
    assertThat(getResp.body()).isEqualTo(PDF);
  }
}
