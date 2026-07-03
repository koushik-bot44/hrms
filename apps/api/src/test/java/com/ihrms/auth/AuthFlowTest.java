package com.ihrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Unified sign-in (§6): EVERYONE — staff (any role) and employees — authenticates with full name +
 * email → OTP. Verifies the token + session shapes, the httpOnly refresh cookie, OTP single-use +
 * expiry, name-matching + enumeration-safety, refresh/me/logout, and the cross-table email guard.
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
  @Autowired AccountEmails accountEmails;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
  }

  // --- Staff sign-in via OTP ------------------------------------------------

  @Test
  void staffSignInViaOtpReturnsUserSessionAndHttpOnlyCookie() throws Exception {
    User admin = staff("Ada Admin", "admin@acme.test", UserRole.SUPER_ADMIN, null);

    String otp = requestOtp("Ada Admin", "admin@acme.test");
    MvcResult res = verifyOtp("admin@acme.test", otp).andExpect(status().isCreated()).andReturn();

    JsonNode body = json.readTree(res.getResponse().getContentAsString());
    assertThat(body.get("accessToken").asText()).isNotBlank();
    JsonNode session = body.get("session");
    assertThat(session.get("type").asText()).isEqualTo("USER");
    assertThat(session.get("userId").asText()).isEqualTo(admin.getId());
    assertThat(session.get("email").asText()).isEqualTo("admin@acme.test");
    assertThat(session.get("role").asText()).isEqualTo("SUPER_ADMIN");
    // companyId/teamId present-but-null for SUPER_ADMIN (contract Session shape).
    assertThat(session.has("companyId")).isTrue();
    assertThat(session.get("companyId").isNull()).isTrue();
    assertThat(session.has("teamId")).isTrue();
    assertThat(session.get("teamId").isNull()).isTrue();

    Cookie cookie = res.getResponse().getCookie("ihrms_refresh");
    assertThat(cookie).isNotNull();
    assertThat(cookie.getValue()).isNotBlank();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/auth");
    assertThat(cookie.getValue()).isNotEqualTo(body.get("accessToken").asText());
  }

  @Test
  void everyRoleSignsInViaOtpIntoTheirScope() throws Exception {
    Company company = new Company();
    company.setName("Acme Inc");
    company.setCode("ACME");
    companies.save(company);

    staff("Sam Super", "super@acme.test", UserRole.SUPER_ADMIN, null);
    User ca = staff("Cara Admin", "ca@acme.test", UserRole.COMPANY_ADMIN, company.getId());
    User hr = staff("Hana HR", "hr@acme.test", UserRole.HR, company.getId());
    User mgr = staff("Max Manager", "mgr@acme.test", UserRole.MANAGER, company.getId());
    Employee emp = employeeUnder(company.getId(), hr.getId(), "Eve Employee", "eve@personal.test");

    assertRole("Sam Super", "super@acme.test", "USER", "SUPER_ADMIN");
    assertRole("Cara Admin", "ca@acme.test", "USER", "COMPANY_ADMIN");
    assertRole("Hana HR", "hr@acme.test", "USER", "HR");
    assertRole("Max Manager", "mgr@acme.test", "USER", "MANAGER");

    // Employee resolves to an EMPLOYEE session (its own-record scope), not a staff role.
    JsonNode empSession = signIn("Eve Employee", "eve@personal.test");
    assertThat(empSession.get("type").asText()).isEqualTo("EMPLOYEE");
    assertThat(empSession.get("employeeId").asText()).isEqualTo(emp.getId());
    assertThat(ca.getId()).isNotEqualTo(mgr.getId()); // (fixtures distinct)
  }

  // --- Employee OTP: name-matched, enumeration-safe, single-use -------------

  @Test
  void otpIsNameMatchedEnumerationSafeAndSingleUse() throws Exception {
    Employee employee = employeeFixture("ACME", "Alex Doe", "alex@personal.test");

    // Unknown email: generic shape, no devOtp leaked.
    assertThat(requestOtpRaw("Nobody At All", "nobody@x.test").has("devOtp")).isFalse();
    // Right email, WRONG name: same generic shape, NO otp issued.
    assertThat(requestOtpRaw("Someone Else", "alex@personal.test").has("devOtp")).isFalse();

    // Correct name + email (case-insensitive, trimmed): devOtp surfaced in non-prod.
    JsonNode ok = requestOtpRaw("  alex doe ", "Alex@Personal.test");
    assertThat(ok.get("sent").asBoolean()).isTrue();
    assertThat(ok.get("expiresInSeconds").asInt()).isPositive();
    String otp = ok.get("devOtp").asText();
    assertThat(otp).hasSize(6);

    MvcResult verified = verifyOtp("alex@personal.test", otp).andExpect(status().isCreated()).andReturn();
    JsonNode session = json.readTree(verified.getResponse().getContentAsString()).get("session");
    assertThat(session.get("type").asText()).isEqualTo("EMPLOYEE");
    assertThat(session.get("employeeId").asText()).isEqualTo(employee.getId());
    assertThat(session.get("employeeCode").isNull()).isTrue(); // no ID until approval (§5)

    // Single-use: the same OTP cannot be replayed.
    verifyOtp("alex@personal.test", otp).andExpect(status().isUnauthorized());
  }

  @Test
  void staffWrongNameDoesNotAuthenticate() throws Exception {
    staff("Real Name", "staff@acme.test", UserRole.HR, null);
    // Right email, wrong name -> no code issued (generic response, no devOtp).
    assertThat(requestOtpRaw("Wrong Name", "staff@acme.test").has("devOtp")).isFalse();
    // And a guessed code cannot verify.
    verifyOtp("staff@acme.test", "000000").andExpect(status().isUnauthorized());
  }

  @Test
  void verifyRejectsExpiredCode() throws Exception {
    Employee employee = employeeFixture("BETA", "Sam Roe", "sam@personal.test");
    employee.setOtpHash(encoder.encode("123456"));
    employee.setOtpExpiresAt(Instant.now().minusSeconds(5));
    employees.save(employee);

    verifyOtp("sam@personal.test", "123456").andExpect(status().isUnauthorized());
  }

  // --- Refresh / me / logout ------------------------------------------------

  @Test
  void refreshRotatesSessionAndMeReturnsSession() throws Exception {
    Company company = new Company();
    company.setName("Acme Inc");
    company.setCode("ACME");
    companies.save(company);
    staff("Hana HR", "hr@acme.test", UserRole.HR, company.getId());

    String otp = requestOtp("Hana HR", "hr@acme.test");
    MvcResult login = verifyOtp("hr@acme.test", otp).andExpect(status().isCreated()).andReturn();
    String accessToken = json.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();
    String refreshToken = login.getResponse().getCookie("ihrms_refresh").getValue();

    MvcResult me =
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode meSession = json.readTree(me.getResponse().getContentAsString());
    assertThat(meSession.get("type").asText()).isEqualTo("USER");
    assertThat(meSession.get("role").asText()).isEqualTo("HR");
    assertThat(meSession.get("companyId").asText()).isEqualTo(company.getId());

    MvcResult refreshed =
        mvc.perform(post("/auth/refresh").cookie(new Cookie("ihrms_refresh", refreshToken)))
            .andExpect(status().isCreated())
            .andReturn();
    assertThat(json.readTree(refreshed.getResponse().getContentAsString()).get("accessToken").asText())
        .isNotBlank();

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

  // --- Cross-table email uniqueness (deterministic resolver) ----------------

  @Test
  void loginEmailIsUniqueAcrossStaffAndEmployees() {
    staff("Staff Person", "shared-staff@acme.test", UserRole.HR, null);
    // Onboarding an employee with a staff email is rejected.
    assertThatThrownBy(() -> accountEmails.assertAvailableForEmployee("shared-staff@acme.test"))
        .isInstanceOf(ResponseStatusException.class);

    employeeFixture("GAMMA", "Emp Person", "shared-emp@personal.test");
    // Creating a staff account with an employee email is rejected.
    assertThatThrownBy(() -> accountEmails.assertAvailableForStaff("shared-emp@personal.test"))
        .isInstanceOf(ResponseStatusException.class);

    // A free email is available for either side.
    accountEmails.assertAvailableForStaff("free@acme.test");
    accountEmails.assertAvailableForEmployee("free@acme.test");
  }

  // --- helpers --------------------------------------------------------------

  private void assertRole(String fullName, String email, String type, String role) throws Exception {
    JsonNode session = signIn(fullName, email);
    assertThat(session.get("type").asText()).isEqualTo(type);
    assertThat(session.get("role").asText()).isEqualTo(role);
  }

  /** Full round-trip: request-otp (grab devOtp) -> verify-otp -> the session node. */
  private JsonNode signIn(String fullName, String email) throws Exception {
    String otp = requestOtp(fullName, email);
    MvcResult verified = verifyOtp(email, otp).andExpect(status().isCreated()).andReturn();
    return json.readTree(verified.getResponse().getContentAsString()).get("session");
  }

  private JsonNode requestOtpRaw(String fullName, String email) throws Exception {
    MvcResult res =
        mvc.perform(
                post("/auth/request-otp")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("fullName", fullName, "email", email))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private String requestOtp(String fullName, String email) throws Exception {
    return requestOtpRaw(fullName, email).get("devOtp").asText();
  }

  private org.springframework.test.web.servlet.ResultActions verifyOtp(String email, String otp)
      throws Exception {
    return mvc.perform(
        post("/auth/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "otp", otp))));
  }

  private User staff(String name, String email, UserRole role, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE"); // no password — sign-in is OTP-only
    return users.save(u);
  }

  private Employee employeeUnder(String companyId, String hrId, String fullName, String email) {
    Employee employee = new Employee();
    employee.setFullName(fullName);
    employee.setEmail(email);
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(hrId);
    return employees.save(employee);
  }

  private Employee employeeFixture(String companyCode, String fullName, String email) {
    Company company = new Company();
    company.setName(companyCode + " Inc");
    company.setCode(companyCode);
    companies.save(company);
    User hr = staff("HR " + companyCode, "hr-" + companyCode.toLowerCase() + "@acme.test", UserRole.HR, company.getId());
    return employeeUnder(company.getId(), hr.getId(), fullName, email);
  }
}
