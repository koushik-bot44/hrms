package com.ihrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.email.Mailer;
import com.ihrms.email.OutboundEmail;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Two TOP-LEVEL sign-in doors, STRICTLY enforced at the API (§6, two-door consolidation): the STAFF door
 * ({@code /login}, audience {@code STAFF}: all staff + platform roles) and the WORKSPACE door
 * ({@code /employee/login}, audience {@code WORKSPACE}: approved, credentialed employees); onboarding stays a
 * separate slugged, token-gated OTP door. A credential valid at the WRONG door is refused AFTER authentication
 * with {@code 403 code:PORTAL_MISMATCH} + a door-appropriate message; each at its OWN door succeeds; a platform
 * role signs in at STAFF; the audience-less API path still accepts both (backward-compat). Also asserts the
 * credential/invite emails carry the TOP-LEVEL door URLs (no slug — the slug is applied only after sign-in).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class SignInDoorsTest {

  private static final String PW = "Passw0rd!";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired MailService mailService;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;

  @SpyBean Mailer mailer;

  private Company acme;
  private User hr;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("Acme Inc", "acme", "acme");
    hr = staff(UserRole.HR, "hana.hr@acme.test");
  }

  // --- strict enforcement: valid credential at the WRONG door -----------------

  @Test
  void employeeCredentialsAtStaffDoorAreRefusedWithWorkspaceHint() throws Exception {
    credentialedEmployee("arjun@acme");
    login("arjun@acme", PW, "STAFF")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PORTAL_MISMATCH"))
        .andExpect(jsonPath("$.message", containsString("workspace sign-in")));
  }

  @Test
  void staffCredentialsAtWorkspaceDoorAreRefusedWithStaffHint() throws Exception {
    login("hana.hr@acme.test", PW, "WORKSPACE")
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("PORTAL_MISMATCH"))
        .andExpect(jsonPath("$.message", containsString("staff sign-in")));
  }

  // --- each audience at its OWN door succeeds ---------------------------------

  @Test
  void eachAudienceAtItsOwnDoorSucceeds() throws Exception {
    credentialedEmployee("arjun@acme");

    JsonNode staffSession = sessionOf(login("hana.hr@acme.test", PW, "STAFF").andExpect(status().isCreated()));
    assertThat(staffSession.get("type").asText()).isEqualTo("USER");
    assertThat(staffSession.get("role").asText()).isEqualTo("HR");

    JsonNode wsSession = sessionOf(login("arjun@acme", PW, "WORKSPACE").andExpect(status().isCreated()));
    assertThat(wsSession.get("type").asText()).isEqualTo("EMPLOYEE");
    assertThat(wsSession.get("mailAddress").asText()).isEqualTo("arjun@acme");
  }

  // --- a platform role signs in at the STAFF door -----------------------------

  @Test
  void platformRoleAtTheStaffDoorSucceeds() throws Exception {
    platformStaff(UserRole.ACCOUNTS_ADMIN, "root.accounts@platform.test");
    JsonNode s =
        sessionOf(login("root.accounts@platform.test", PW, "STAFF").andExpect(status().isCreated()));
    assertThat(s.get("type").asText()).isEqualTo("USER");
    assertThat(s.get("role").asText()).isEqualTo("ACCOUNTS_ADMIN");
  }

  // --- the audience-less API path still accepts both (backward-compat) --------

  @Test
  void audienceLessLoginAcceptsBoth() throws Exception {
    credentialedEmployee("arjun@acme");
    // No audience -> no restriction (legacy /auth/login callers; no UI door sends this now).
    assertThat(sessionOf(login("hana.hr@acme.test", PW, null).andExpect(status().isCreated())).get("type").asText())
        .isEqualTo("USER");
    assertThat(sessionOf(login("arjun@acme", PW, null).andExpect(status().isCreated())).get("type").asText())
        .isEqualTo("EMPLOYEE");
  }

  // --- emails carry the TOP-LEVEL door URLs (no slug) -------------------------

  @Test
  void credentialEmailForAnEmployeePointsAtTheTopLevelWorkspaceDoor() throws Exception {
    Employee emp = approvedNoCreds();
    String hrToken =
        tokens.issueAccess(
            new IhrmsPrincipal.User(hr.getId(), hr.getEmail(), "Hana HR", UserRole.HR, acme.getId(), null));
    mvc.perform(post("/employees/" + emp.getId() + "/credentials")
            .header("Authorization", "Bearer " + hrToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("localPart", "arjun"))))
        .andExpect(status().isCreated());

    ArgumentCaptor<OutboundEmail> captor = ArgumentCaptor.forClass(OutboundEmail.class);
    verify(mailer, timeout(3000)).send(captor.capture());
    String text = captor.getValue().text();
    assertThat(text).contains("/employee/login"); // the top-level workspace door
    assertThat(text).doesNotContain("/acme"); // NO slug — applied only after sign-in
    assertThat(text).doesNotContain("/workspace/login");
  }

  @Test
  void staffInviteEmailPointsAtTheTopLevelStaffDoor() throws Exception {
    mailService.sendCompanyAdminInvite("new.admin@ext.test", "Acme Inc", "TempPw@1", acme.getId());

    ArgumentCaptor<OutboundEmail> captor = ArgumentCaptor.forClass(OutboundEmail.class);
    verify(mailer, timeout(3000)).send(captor.capture());
    String text = captor.getValue().text();
    assertThat(text).contains("/login"); // the top-level staff door
    assertThat(text).doesNotContain("/acme"); // NO slug
    assertThat(text).doesNotContain("/employee/login");
    assertThat(text).doesNotContain("/workspace/login");
  }

  // --- helpers ---------------------------------------------------------------

  private ResultActions login(String email, String pw, String audience) throws Exception {
    Map<String, Object> body = new HashMap<>();
    body.put("email", email);
    body.put("password", pw);
    if (audience != null) body.put("audience", audience);
    return mvc.perform(
        post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
  }

  private JsonNode sessionOf(ResultActions actions) throws Exception {
    return json.readTree(actions.andReturn().getResponse().getContentAsString()).get("session");
  }

  private Company company(String name, String code, String slug) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    c.setSlug(slug);
    c.setMailDomain(slug);
    return companies.save(c);
  }

  private User staff(UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name());
    u.setRole(role);
    u.setCompanyId(acme.getId());
    u.setPasswordHash(encoder.encode(PW));
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  /** A PLATFORM staff user (no company) — e.g. Accounts Admin / Super Admin / Hierarchy — signs in at STAFF. */
  private User platformStaff(UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name());
    u.setRole(role);
    u.setCompanyId(null);
    u.setPasswordHash(encoder.encode(PW));
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  /** An approved, CREDENTIALED employee (mailbox address + password) — a workspace user. */
  private Employee credentialedEmployee(String mailAddress) {
    Employee e = approvedNoCreds();
    e.setMailAddress(mailAddress);
    e.setPasswordHash(encoder.encode(PW));
    return employees.save(e);
  }

  /** An approved employee onboarded by {@code hr}, no credentials yet (ready for assign). */
  private Employee approvedNoCreds() {
    Employee e = new Employee();
    e.setEmployeeCode("ACME-EMP-000001");
    e.setFullName("Arjun Rao");
    e.setEmail("arjun.personal@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(acme.getId());
    e.setOnboardingHrId(hr.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }
}
