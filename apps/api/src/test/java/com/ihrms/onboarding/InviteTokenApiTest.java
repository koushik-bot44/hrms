package com.ihrms.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.InviteTokenRepository;
import com.ihrms.domain.repository.UserRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The INVITE-LINK-ONLY onboarding door (§3.2/§6): the emailed link carries a signed, expiring token; the public
 * validate endpoint and BOTH OTP endpoints require a valid one. Covers link-carries-token, validate OK vs the
 * single generic failure, OTP request+verify both rejecting a missing/invalid/expired token, resend revoking the
 * previous link, resend scope + INVITED-only, a completed employee's token dying, validate rate limiting, and
 * the V37 backfill for existing INVITED employees. DevLogMailer under test (zero network).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class InviteTokenApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired InviteTokenService inviteTokens;
  @Autowired InviteTokenRepository inviteTokenRepo;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired JdbcTemplate jdbc;

  private Company company;
  private User hr;
  private String hrToken;
  private Employee employee;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\","
            + "\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    company = company("Acme Inc", "ACME");
    hr = staff(UserRole.HR, "hana.hr@acme.test");
    hrToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(hr.getId(), hr.getEmail(), "Hana HR", UserRole.HR, company.getId(), null));
    employee = employee("Alex Doe", "alex@personal.test", EmployeeStatus.INVITED, hr.getId());
  }

  // --- the link carries a token, and validate returns the door's context ------

  @Test
  void resendLinkCarriesATokenThatValidates() throws Exception {
    String loginUrl = resend(hrToken, employee.getId()).get("loginUrl").asText();
    assertThat(loginUrl).contains("/acme/employee/login").contains("token=").contains("email=");

    String token = tokenFromUrl(loginUrl);
    validate(token)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.email").value("alex@personal.test"))
        .andExpect(jsonPath("$.companySlug").value("acme"))
        .andExpect(jsonPath("$.companyName").value("Acme Inc"));
  }

  // --- validate: OK for a fresh token; ONE generic 410 for expired/revoked/unknown ---

  @Test
  void validateGivesOneGenericFailureForExpiredRevokedOrUnknown() throws Exception {
    // Unknown / garbage token.
    validate("never-existed").andExpect(status().isGone());

    // Expired.
    String expired = inviteTokens.issueFor(employee.getId());
    var row = inviteTokenRepo.findByTokenHash(InviteTokenService.sha256Hex(expired)).orElseThrow();
    row.setExpiresAt(Instant.now().minusSeconds(60));
    inviteTokenRepo.save(row);
    validate(expired).andExpect(status().isGone());

    // Revoked: issuing a new token revokes the prior one.
    String first = inviteTokens.issueFor(employee.getId());
    String second = inviteTokens.issueFor(employee.getId());
    validate(first).andExpect(status().isGone());
    validate(second).andExpect(status().isOk());
  }

  // --- the real gate: OTP request AND verify both require a valid token --------

  @Test
  void bothOtpEndpointsRejectAMissingInvalidOrExpiredToken() throws Exception {
    String token = inviteTokens.issueFor(employee.getId());

    // Missing token -> 400 on both.
    mvc.perform(otp("/auth/request-otp", Map.of("fullName", "Alex Doe", "email", "alex@personal.test")))
        .andExpect(status().isBadRequest());
    mvc.perform(otp("/auth/verify-otp", Map.of("email", "alex@personal.test", "otp", "000000")))
        .andExpect(status().isBadRequest());

    // Tampered token -> 401 on both.
    requestOtp("Alex Doe", "alex@personal.test", token + "x").andExpect(status().isUnauthorized());
    verifyOtp("alex@personal.test", "000000", token + "x").andExpect(status().isUnauthorized());

    // Valid token -> request issues a code, verify signs in.
    String code =
        json.readTree(
                requestOtp("Alex Doe", "alex@personal.test", token)
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .get("devOtp")
            .asText();
    verifyOtp("alex@personal.test", code, token).andExpect(status().isCreated());
  }

  // --- resend revokes the previous link; the new one works --------------------

  @Test
  void resendRevokesThePreviousLink() throws Exception {
    String oldToken = tokenFromUrl(resend(hrToken, employee.getId()).get("loginUrl").asText());
    String newToken = tokenFromUrl(resend(hrToken, employee.getId()).get("loginUrl").asText());

    // The old link is dead everywhere.
    validate(oldToken).andExpect(status().isGone());
    requestOtp("Alex Doe", "alex@personal.test", oldToken).andExpect(status().isUnauthorized());

    // The new link works.
    validate(newToken).andExpect(status().isOk());
    requestOtp("Alex Doe", "alex@personal.test", newToken).andExpect(status().isCreated());

    // INVITE_RESENT is audited.
    assertThat(jdbc.queryForList("SELECT \"action\" FROM \"audit_logs\"", String.class))
        .contains("INVITE_RESENT");
  }

  // --- resend is HR-scoped (own onboarded) and INVITED-only -------------------

  @Test
  void resendIsScopedToOwnOnboardedAndInvitedOnly() throws Exception {
    // A DIFFERENT HR (not the onboarding HR) cannot resend -> 404 (existence hidden).
    User otherHr = staff(UserRole.HR, "raj.hr@acme.test");
    String otherToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(
                otherHr.getId(), otherHr.getEmail(), "Raj HR", UserRole.HR, company.getId(), null));
    mvc.perform(post("/employees/" + employee.getId() + "/invite/resend")
            .header("Authorization", "Bearer " + otherToken))
        .andExpect(status().isNotFound());

    // Once the employee is past INVITED, resend is a 409.
    employee.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(employee);
    mvc.perform(post("/employees/" + employee.getId() + "/invite/resend")
            .header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isConflict());
  }

  // --- a completed/approved employee's token no longer authorizes the door ----

  @Test
  void anApprovedEmployeesTokenNoLongerWorks() throws Exception {
    String token = inviteTokens.issueFor(employee.getId());
    validate(token).andExpect(status().isOk()); // still onboarding

    employee.setStatus(EmployeeStatus.APPROVED); // onboarding complete -> moves to workspace credentials
    employees.save(employee);

    validate(token).andExpect(status().isGone());
    requestOtp("Alex Doe", "alex@personal.test", token).andExpect(status().isUnauthorized());
  }

  // --- validate is rate-limited (RateLimitFilter over /public/onboarding/**) --

  @Test
  void validateIsRateLimited() throws Exception {
    RequestPostProcessor ip = req -> {
      req.setRemoteAddr("77.77.77.77");
      return req;
    };
    int last = 200;
    for (int i = 0; i < 70; i++) {
      last =
          mvc.perform(post("/public/onboarding/invite/validate")
                  .with(ip)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(json.writeValueAsString(Map.of("token", "x"))))
              .andReturn()
              .getResponse()
              .getStatus();
    }
    assertThat(last).isEqualTo(429);
  }

  // --- V37 backfill: existing INVITED employees are issued a token ------------

  @Test
  void migrationBackfillIssuesTokensForInvitedEmployees() {
    // `employee` was created (INVITED) WITHOUT going through the invite path, so it has no token yet —
    // exactly like an in-flight employee at deploy time. Run the V37 backfill statement.
    assertThat(inviteTokenRepo.findByEmployeeIdAndRevokedAtIsNull(employee.getId())).isEmpty();
    jdbc.execute(BACKFILL_SQL);
    assertThat(inviteTokenRepo.findByEmployeeIdAndRevokedAtIsNull(employee.getId())).hasSize(1);

    // Idempotent: re-running does not double-issue (the NOT EXISTS guard).
    jdbc.execute(BACKFILL_SQL);
    assertThat(inviteTokenRepo.findByEmployeeIdAndRevokedAtIsNull(employee.getId())).hasSize(1);
  }

  /** The V37 backfill statement (kept in lockstep with the migration). */
  private static final String BACKFILL_SQL =
      "INSERT INTO \"employee_invite_tokens\" (\"id\", \"employeeId\", \"tokenHash\", \"expiresAt\", \"createdAt\") "
          + "SELECT md5(gen_random_uuid()::text) || md5(gen_random_uuid()::text || e.\"id\"), e.\"id\", "
          + "md5(gen_random_uuid()::text) || md5(e.\"id\" || gen_random_uuid()::text), now() + interval '7 days', now() "
          + "FROM \"employees\" e WHERE e.\"status\" = 'INVITED' "
          + "AND NOT EXISTS (SELECT 1 FROM \"employee_invite_tokens\" t "
          + "WHERE t.\"employeeId\" = e.\"id\" AND t.\"revokedAt\" IS NULL AND t.\"expiresAt\" > now())";

  // --- helpers ---------------------------------------------------------------

  // A dedicated client IP so this class's rate-limited requests (/auth/**, /public/onboarding/**) never share
  // the per-IP counter with the default 127.0.0.1 used by other test classes (the RateLimitFilter is a shared
  // singleton). The rate-limit test below uses its OWN IP so its burst never spills into these.
  private static final RequestPostProcessor CLIENT = req -> {
    req.setRemoteAddr("44.44.44.44");
    return req;
  };

  private ResultActions validate(String token) throws Exception {
    return mvc.perform(post("/public/onboarding/invite/validate")
        .with(CLIENT)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(Map.of("token", token))));
  }

  private JsonNode resend(String token, String employeeId) throws Exception {
    MvcResult res =
        mvc.perform(post("/employees/" + employeeId + "/invite/resend")
                .with(CLIENT)
                .header("Authorization", "Bearer " + token))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(res.getResponse().getContentAsString());
  }

  private ResultActions requestOtp(String fullName, String email, String token) throws Exception {
    return mvc.perform(otp("/auth/request-otp", Map.of("fullName", fullName, "email", email, "token", token)));
  }

  private ResultActions verifyOtp(String email, String otp, String token) throws Exception {
    return mvc.perform(otp("/auth/verify-otp", Map.of("email", email, "otp", otp, "token", token)));
  }

  private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder otp(
      String path, Map<String, Object> body) throws Exception {
    return post(path)
        .with(CLIENT)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private static String tokenFromUrl(String loginUrl) {
    String rest = loginUrl.substring(loginUrl.indexOf("token=") + "token=".length());
    int amp = rest.indexOf('&');
    return amp >= 0 ? rest.substring(0, amp) : rest;
  }

  private Company company(String name, String code) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    return companies.save(c);
  }

  private User staff(UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name());
    u.setRole(role);
    u.setCompanyId(company.getId());
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Employee employee(String fullName, String email, EmployeeStatus status, String hrId) {
    Employee e = new Employee();
    e.setFullName(fullName);
    e.setEmail(email);
    e.setCompanyId(company.getId());
    e.setOnboardingHrId(hrId);
    e.setStatus(status);
    return employees.save(e);
  }
}
