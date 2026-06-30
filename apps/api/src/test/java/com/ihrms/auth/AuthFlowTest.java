package com.ihrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Staff login + employee OTP flows against the real contract (§3.2): status codes, token +
 * session shapes, the httpOnly refresh cookie, OTP single-use + expiry, and refresh/logout.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuthFlowTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired CompanyRepository companies;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
  }

  // --- Staff login ----------------------------------------------------------

  @Test
  void staffLoginReturnsAccessTokenUserSessionAndHttpOnlyCookie() throws Exception {
    User admin = staff("admin@acme.test", UserRole.SUPER_ADMIN, null, "Password@123");

    MvcResult res =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("email", "admin@acme.test", "password", "Password@123"))))
            .andExpect(status().isCreated())
            .andReturn();

    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("accessToken").asText()).isNotBlank();
    JsonNode session = body.get("session");
    assertThat(session.get("type").asText()).isEqualTo("USER");
    assertThat(session.get("userId").asText()).isEqualTo(admin.getId());
    assertThat(session.get("email").asText()).isEqualTo("admin@acme.test");
    assertThat(session.get("role").asText()).isEqualTo("SUPER_ADMIN");
    // companyId/teamId are present-but-null for SUPER_ADMIN (contract Session shape).
    assertThat(session.has("companyId")).isTrue();
    assertThat(session.get("companyId").isNull()).isTrue();
    assertThat(session.has("teamId")).isTrue();
    assertThat(session.get("teamId").isNull()).isTrue();

    Cookie cookie = res.getResponse().getCookie("ihrms_refresh");
    assertThat(cookie).isNotNull();
    assertThat(cookie.getValue()).isNotBlank();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/auth");
    // The access token must NOT be the refresh token (distinct secrets/claims).
    assertThat(cookie.getValue()).isNotEqualTo(body.get("accessToken").asText());
  }

  @Test
  void staffLoginWithWrongPasswordReturns401Envelope() throws Exception {
    staff("admin@acme.test", UserRole.SUPER_ADMIN, null, "Password@123");

    MvcResult res =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("email", "admin@acme.test", "password", "WrongPass@1"))))
            .andExpect(status().isUnauthorized())
            .andReturn();

    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("statusCode").asInt()).isEqualTo(401);
    assertThat(body.get("error").asText()).isEqualTo("UNAUTHORIZED");
    assertThat(body.get("message").asText()).isEqualTo("Invalid email or password");
    assertThat(body.get("path").asText()).isEqualTo("/auth/login");
    assertThat(res.getResponse().getCookie("ihrms_refresh")).isNull();
  }

  // --- Employee OTP ---------------------------------------------------------

  @Test
  void employeeOtpRequestIsEnumerationSafeAndVerifyIsSingleUse() throws Exception {
    Employee employee = employeeFixture("ACME", "ACME-EMP-000001", "alex@personal.test");

    // Unknown code: same shape, but no devOtp leaked.
    MvcResult unknown =
        mvc.perform(
                post("/auth/employee/request-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("employeeCode", "ACME-EMP-999999", "email", "nobody@x.test"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode unknownBody = json.readTree(unknown.getResponse().getContentAsString());
    assertThat(unknownBody.get("sent").asBoolean()).isTrue();
    assertThat(unknownBody.has("devOtp")).isFalse();

    // Real code+email: devOtp surfaced in non-prod so the flow can be walked.
    MvcResult requested =
        mvc.perform(
                post("/auth/employee/request-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "employeeCode", "ACME-EMP-000001", "email", "alex@personal.test"))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode requestedBody = json.readTree(requested.getResponse().getContentAsString());
    assertThat(requestedBody.get("sent").asBoolean()).isTrue();
    assertThat(requestedBody.get("expiresInSeconds").asInt()).isPositive();
    String otp = requestedBody.get("devOtp").asText();
    assertThat(otp).hasSize(6);

    // Verify succeeds -> EMPLOYEE session.
    MvcResult verified =
        mvc.perform(
                post("/auth/employee/verify-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("employeeCode", "ACME-EMP-000001", "otp", otp))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode session = json.readTree(verified.getResponse().getContentAsString()).get("session");
    assertThat(session.get("type").asText()).isEqualTo("EMPLOYEE");
    assertThat(session.get("employeeId").asText()).isEqualTo(employee.getId());
    assertThat(session.get("employeeCode").asText()).isEqualTo("ACME-EMP-000001");

    // Single-use: the same OTP cannot be replayed.
    mvc.perform(
            post("/auth/employee/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(Map.of("employeeCode", "ACME-EMP-000001", "otp", otp))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void employeeOtpRejectsExpiredCode() throws Exception {
    Employee employee = employeeFixture("BETA", "BETA-EMP-000001", "sam@personal.test");
    // Plant a known OTP that already expired.
    employee.setOtpHash(encoder.encode("123456"));
    employee.setOtpExpiresAt(Instant.now().minusSeconds(5));
    employees.save(employee);

    mvc.perform(
            post("/auth/employee/verify-otp")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of("employeeCode", "BETA-EMP-000001", "otp", "123456"))))
        .andExpect(status().isUnauthorized());
  }

  // --- Refresh / me / logout ------------------------------------------------

  @Test
  void refreshRotatesSessionAndMeReturnsSession() throws Exception {
    Company company = new Company();
    company.setName("Acme Inc");
    company.setCode("ACME");
    companies.save(company);
    staff("hr@acme.test", UserRole.HR, company.getId(), "Password@123");

    MvcResult login =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of("email", "hr@acme.test", "password", "Password@123"))))
            .andExpect(status().isCreated())
            .andReturn();
    String accessToken = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    String refreshToken = login.getResponse().getCookie("ihrms_refresh").getValue();

    // /auth/me with the access token returns the same session.
    MvcResult me =
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode meSession = json.readTree(me.getResponse().getContentAsString());
    assertThat(meSession.get("type").asText()).isEqualTo("USER");
    assertThat(meSession.get("role").asText()).isEqualTo("HR");
    assertThat(meSession.get("companyId").asText()).isEqualTo(company.getId());

    // Refresh with the cookie issues a fresh access token + session.
    MvcResult refreshed =
        mvc.perform(post("/auth/refresh").cookie(new Cookie("ihrms_refresh", refreshToken)))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(json.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asText())
        .isNotBlank();

    // Logout clears the cookie (Max-Age=0) and returns { ok: true }.
    MvcResult out = mvc.perform(post("/auth/logout")).andExpect(status().isCreated()).andReturn();
    assertThat(json.readTree(out.getResponse().getContentAsString()).get("ok").asBoolean()).isTrue();
    Cookie cleared = out.getResponse().getCookie("ihrms_refresh");
    assertThat(cleared).isNotNull();
    assertThat(cleared.getMaxAge()).isZero();
  }

  @Test
  void meRequiresAuthentication() throws Exception {
    mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());
  }

  // --- fixtures -------------------------------------------------------------

  private User staff(String email, UserRole role, String companyId, String password) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setPasswordHash(encoder.encode(password));
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Employee employeeFixture(String companyCode, String employeeCode, String email) {
    Company company = new Company();
    company.setName(companyCode + " Inc");
    company.setCode(companyCode);
    companies.save(company);

    User hr = staff("hr-" + companyCode.toLowerCase() + "@acme.test", UserRole.HR, company.getId(), "Password@123");

    Employee employee = new Employee();
    employee.setEmployeeCode(employeeCode);
    employee.setEmail(email);
    employee.setCompanyId(company.getId());
    employee.setOnboardingHrId(hr.getId());
    return employees.save(employee);
  }
}
