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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

/**
 * Two-audience sign-in (§6): <b>staff</b> (any role) authenticate with email + password at
 * {@code /auth/login} (User only); <b>employees</b> authenticate with full name + email → OTP at
 * {@code /auth/request-otp}+{@code /auth/verify-otp} (Employee only). Verifies token/session shapes,
 * the httpOnly refresh cookie, cross-audience denial both ways, OTP single-use + expiry + name-match
 * + enumeration-safety, self-service change-password, refresh/me/logout, and the cross-table guard.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuthFlowTest {

  private static final String STAFF_PW = "Passw0rd!";

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

  // --- Staff: email + password ----------------------------------------------

  @Test
  void staffSignInWithPasswordReturnsUserSessionAndHttpOnlyCookie() throws Exception {
    User admin = staff("Ada Admin", "admin@acme.test", UserRole.SUPER_ADMIN, null);

    MvcResult res = login("admin@acme.test", STAFF_PW).andExpect(status().isCreated()).andReturn();

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
  void everyStaffRoleSignsInWithPasswordIntoTheirScope() throws Exception {
    Company company = company("Acme Inc", "ACME");
    staff("Sam Super", "super@acme.test", UserRole.SUPER_ADMIN, null);
    staff("Cara Admin", "ca@acme.test", UserRole.COMPANY_ADMIN, company.getId());
    staff("Hana HR", "hr@acme.test", UserRole.HR, company.getId());
    staff("Max Manager", "mgr@acme.test", UserRole.MANAGER, company.getId());

    assertStaffRole("super@acme.test", "SUPER_ADMIN");
    assertStaffRole("ca@acme.test", "COMPANY_ADMIN");
    assertStaffRole("hr@acme.test", "HR");
    assertStaffRole("mgr@acme.test", "MANAGER");
  }

  @Test
  void wrongPasswordAndUnknownEmailGetGenericDenial() throws Exception {
    staff("Ada Admin", "admin@acme.test", UserRole.SUPER_ADMIN, null);
    login("admin@acme.test", "wrong-password").andExpect(status().isUnauthorized());
    login("nobody@acme.test", STAFF_PW).andExpect(status().isUnauthorized());
  }

  @Test
  void employeeCannotUseStaffPasswordLogin() throws Exception {
    employeeFixture("ACME", "Eve Employee", "eve@personal.test");
    // An employee email at the staff endpoint resolves no User -> generic denial.
    login("eve@personal.test", STAFF_PW).andExpect(status().isUnauthorized());
  }

  // --- Change password (staff, authenticated) -------------------------------

  @Test
  void changePasswordRequiresCurrentAndTakesEffect() throws Exception {
    staff("Hana HR", "hr@acme.test", UserRole.HR, company("Acme Inc", "ACME").getId());
    String token =
        json.readTree(login("hr@acme.test", STAFF_PW).andReturn().getResponse().getContentAsString())
            .get("accessToken")
            .asText();

    // Wrong current password -> rejected, nothing changes.
    changePassword(token, "not-my-password", "BrandNew@1").andExpect(status().isBadRequest());

    // Correct current -> updated.
    changePassword(token, STAFF_PW, "BrandNew@1").andExpect(status().isCreated());

    // Old password no longer works; the new one does.
    login("hr@acme.test", STAFF_PW).andExpect(status().isUnauthorized());
    login("hr@acme.test", "BrandNew@1").andExpect(status().isCreated());

    assertThat(auditActions()).contains("PASSWORD_CHANGED");
  }

  @Test
  void changePasswordEnforcesMinLength() throws Exception {
    staff("Ada Admin", "admin@acme.test", UserRole.SUPER_ADMIN, null);
    String token =
        json.readTree(login("admin@acme.test", STAFF_PW).andReturn().getResponse().getContentAsString())
            .get("accessToken")
            .asText();
    changePassword(token, STAFF_PW, "short").andExpect(status().isBadRequest());
  }

  // --- Employee OTP: name-matched, enumeration-safe, single-use -------------

  @Test
  void employeeOtpIsNameMatchedEnumerationSafeAndSingleUse() throws Exception {
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
  void staffCannotUseEmployeeOtp() throws Exception {
    staff("Real Name", "staff@acme.test", UserRole.HR, null);
    // A staff email at the employee endpoint resolves no Employee -> no code (generic response).
    assertThat(requestOtpRaw("Real Name", "staff@acme.test").has("devOtp")).isFalse();
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
    Company company = company("Acme Inc", "ACME");
    staff("Hana HR", "hr@acme.test", UserRole.HR, company.getId());

    MvcResult login = login("hr@acme.test", STAFF_PW).andExpect(status().isCreated()).andReturn();
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

  private void assertStaffRole(String email, String role) throws Exception {
    JsonNode session =
        json.readTree(login(email, STAFF_PW).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
            .get("session");
    assertThat(session.get("type").asText()).isEqualTo("USER");
    assertThat(session.get("role").asText()).isEqualTo(role);
  }

  private ResultActions login(String email, String password) throws Exception {
    return mvc.perform(
        post("/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "password", password))));
  }

  private ResultActions changePassword(String token, String current, String next) throws Exception {
    return mvc.perform(
        post("/auth/change-password")
            .header("Authorization", "Bearer " + token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
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

  private ResultActions verifyOtp(String email, String otp) throws Exception {
    return mvc.perform(
        post("/auth/verify-otp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("email", email, "otp", otp))));
  }

  private Company company(String name, String code) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    return companies.save(c);
  }

  private User staff(String name, String email, UserRole role, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(name);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setPasswordHash(encoder.encode(STAFF_PW)); // staff sign in with email + password (§6)
    u.setStatus("ACTIVE");
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
    Company company = company(companyCode + " Inc", companyCode);
    User hr = staff("HR " + companyCode, "hr-" + companyCode.toLowerCase() + "@acme.test", UserRole.HR, company.getId());
    return employeeUnder(company.getId(), hr.getId(), fullName, email);
  }

  private java.util.List<String> auditActions() {
    return jdbc.queryForList("SELECT \"action\" FROM \"audit_logs\"", String.class);
  }
}
