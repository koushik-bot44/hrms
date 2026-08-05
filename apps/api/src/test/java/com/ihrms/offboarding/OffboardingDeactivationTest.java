package com.ihrms.offboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.AuthService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.auth.dto.AuthDtos.OtpRequest;
import com.ihrms.auth.dto.AuthDtos.OtpVerifyRequest;
import com.ihrms.auth.dto.AuthDtos.StaffLoginRequest;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.OffboardingDocument;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.time.LocalDate;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.server.ResponseStatusException;

/**
 * The corrected §3.6 semantics: completion (OFFBOARDED) NO LONGER blocks login on EITHER door; the explicit HR
 * "Deactivate account" is what disables both doors + refresh. Plus the fixed letter gate (APPROVED and
 * COMPLETED, verified) and the HR letter-requests inbox (scope + pending count).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class OffboardingDeactivationTest {

  private static final String PASSWORD = "Secret@123";
  private static final String MAILBOX = "meera@work.mail";
  private static final String PERSONAL = "meera@personal.test";
  private static final String FULLNAME = "Meera Nair";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired AuthService auth;
  @Autowired TokenService tokens;
  @Autowired PasswordEncoder encoder;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired OffboardingCaseRepository cases;
  @Autowired OffboardingDocumentRepository docs;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;

  private String companyA;
  private User hrA;
  private User hrB;
  private String hrAToken;
  private String hrBToken;
  private Employee empA;
  private String empAToken;
  private OffboardingCase caseA;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"form1_personal\",\"form2_info\","
            + "\"form3_prev_employment\",\"documents\",\"document_requests\",\"request_documents\","
            + "\"signatures\",\"generated_documents\",\"employee_agreements\",\"offboarding_letters\","
            + "\"offboarding_documents\",\"offboarding_clearance\",\"offboarding_cases\",\"approval_requests\","
            + "\"notifications\",\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");

    companyA = company("Globex Corporation", "GLBX");
    hrA = user(companyA, UserRole.HR, "asha.hr@globex.test");
    hrB = user(companyA, UserRole.HR, "raj.hr@globex.test"); // a DIFFERENT HR (scope check)
    User mgr = user(companyA, UserRole.MANAGER, "mgr@globex.test");
    team(companyA, hrA.getId(), mgr.getId());
    hrAToken = tokenFor(hrA);
    hrBToken = tokenFor(hrB);

    empA = approvedEmployee(companyA, hrA.getId(), "GLBX-EMP-000001");
    empAToken =
        tokens.issueAccess(
            new IhrmsPrincipal.Employee(empA.getId(), empA.getEmployeeCode(), empA.getEmail(), companyA));
    caseA = offboardingCase(empA.getId(), hrA.getId());

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://s/get");
    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenAnswer(inv -> "companies/x/" + inv.getArgument(3));
  }

  // --- completion no longer blocks either door ------------------------------

  @Test
  void completionDoesNotBlockEitherDoorNorRefresh() {
    // Complete: OFFBOARDED but NOT deactivated.
    empA.setStatus(EmployeeStatus.OFFBOARDED);
    employees.save(empA);

    // Credentialed workspace login still works.
    assertThatCode(() -> auth.loginStaff(new StaffLoginRequest(MAILBOX, PASSWORD))).doesNotThrowAnyException();

    // OTP door: a code is still issued, and verify still yields a session.
    String otp = auth.requestOtp(new OtpRequest(FULLNAME, PERSONAL)).devOtp();
    assertThat(otp).isNotNull();
    assertThat(auth.verifyOtp(new OtpVerifyRequest(PERSONAL, otp))).isNotNull();

    // Refresh still works.
    String refresh =
        tokens.issueRefresh(new IhrmsPrincipal.Employee(empA.getId(), empA.getEmployeeCode(), empA.getEmail(), companyA), "OTP");
    assertThat(auth.refresh(refresh)).isNotNull();
  }

  // --- deactivation blocks both doors + both refresh paths ------------------

  @Test
  void deactivationBlocksBothDoorsAndRefresh() {
    empA.setStatus(EmployeeStatus.OFFBOARDED);
    empA.setAccountDeactivated(true);
    employees.save(empA);

    // Credential login -> 403 deactivated.
    assertThatThrownBy(() -> auth.loginStaff(new StaffLoginRequest(MAILBOX, PASSWORD)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(statusOf(e)).isEqualTo(403));

    // OTP request withholds the code (enumeration-safe); verify -> 403 deactivated.
    assertThat(auth.requestOtp(new OtpRequest(FULLNAME, PERSONAL)).devOtp()).isNull();
    assertThatThrownBy(() -> auth.verifyOtp(new OtpVerifyRequest(PERSONAL, "000000")))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(statusOf(e)).isEqualTo(403));

    // Refresh dies for BOTH employee session types.
    for (String method : new String[] {"OTP", "PASSWORD"}) {
      String refresh =
          tokens.issueRefresh(new IhrmsPrincipal.Employee(empA.getId(), empA.getEmployeeCode(), empA.getEmail(), companyA), method);
      assertThatThrownBy(() -> auth.refresh(refresh))
          .isInstanceOf(ResponseStatusException.class)
          .satisfies(e -> assertThat(statusOf(e)).isEqualTo(403));
    }
  }

  // --- the deactivate endpoint: guard + idempotency + audit -----------------

  @Test
  void deactivateEndpointRequiresOffboardedIdempotentAudited() throws Exception {
    // Not offboarded yet -> 409.
    deactivate(hrAToken).andExpect(status().isConflict());

    empA.setStatus(EmployeeStatus.OFFBOARDED);
    employees.save(empA);

    deactivate(hrAToken).andExpect(status().isOk());
    assertThat(employees.findById(empA.getId()).orElseThrow().isAccountDeactivated()).isTrue();
    assertThat(auditLogs.findByAction("ACCOUNT_DEACTIVATED")).hasSize(1);

    // Idempotent guard: a second deactivate -> 409, no second audit.
    deactivate(hrAToken).andExpect(status().isConflict());
    assertThat(auditLogs.findByAction("ACCOUNT_DEACTIVATED")).hasSize(1);
  }

  // --- the corrected letter gate matrix -------------------------------------

  @Test
  void letterGateAllowsApprovedAndCompletedWhenVerified() throws Exception {
    // Pre-verification: a sent-but-unverified document keeps the gate closed.
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.SUBMITTED);
    requestLetter(empAToken, "RELIEVING_LETTER").andExpect(status().isConflict());

    // APPROVED + all verified -> requestable.
    verifyDoc(OffboardingDocType.EXIT_FORMALITIES);
    requestLetter(empAToken, "RELIEVING_LETTER").andExpect(status().isOk());

    // COMPLETED + all verified -> STILL requestable (the primary post-completion scenario).
    caseA.setStatus(OffboardingStatus.COMPLETED);
    cases.save(caseA);
    empA.setStatus(EmployeeStatus.OFFBOARDED);
    employees.save(empA);
    requestLetter(empAToken, "EXPERIENCE_LETTER").andExpect(status().isOk());

    // HR can ISSUE post-completion.
    issue(hrAToken, "EXPERIENCE_LETTER").andExpect(status().isOk());
  }

  // --- the HR letter-requests inbox: scope + pending count ------------------

  @Test
  void hrLetterInboxIsScopedAndCountsPending() throws Exception {
    doc(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocStatus.VERIFIED);
    requestLetter(empAToken, "RELIEVING_LETTER").andExpect(status().isOk());

    // The routed HR sees it, pending.
    JsonNode mine = hrInbox(hrAToken);
    assertThat(mine.get("requests")).hasSize(1);
    assertThat(mine.get("pendingCount").asLong()).isEqualTo(1);
    assertThat(mine.get("requests").get(0).get("employeeCode").asText()).isEqualTo("GLBX-EMP-000001");

    // A DIFFERENT HR sees nothing (scope enforced).
    JsonNode foreign = hrInbox(hrBToken);
    assertThat(foreign.get("requests")).isEmpty();
    assertThat(foreign.get("pendingCount").asLong()).isZero();

    // Issuing resolves the request -> pending drops, status reflects it.
    issue(hrAToken, "RELIEVING_LETTER").andExpect(status().isOk());
    JsonNode after = hrInbox(hrAToken);
    assertThat(after.get("pendingCount").asLong()).isZero();
    assertThat(after.get("requests").get(0).get("status").asText()).isEqualTo("RESOLVED");
  }

  // --- helpers --------------------------------------------------------------

  private static int statusOf(Throwable e) {
    return ((ResponseStatusException) e).getStatusCode().value();
  }

  private ResultActions deactivate(String token) throws Exception {
    return mvc.perform(post("/employees/" + empA.getId() + "/deactivate").header("Authorization", "Bearer " + token));
  }

  private ResultActions requestLetter(String token, String type) throws Exception {
    return mvc.perform(post("/me/offboarding/letters/" + type)
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{}"));
  }

  private ResultActions issue(String token, String type) throws Exception {
    Map<String, Object> body =
        type.equals("RELIEVING_LETTER")
            ? Map.of("hrValues", Map.of(
                "DATE", "16/04/2026", "RESIGNATION_DATE", "04/05/2023", "RELIEVING_DATE", "09/05/2023",
                "TENURE_FROM", "03/04/2023", "TENURE_TO", "09/05/2023", "DESIGNATION", "Engineer"))
            : Map.of(
                "hrValues", Map.of("DATE", "26/12/2024", "DESIGNATION", "Engineer",
                    "TENURE_FROM", "19/06/2023", "TENURE_TO", "12/12/2024"),
                "gender", "MALE");
    return mvc.perform(post("/employees/" + empA.getId() + "/offboarding/letters/" + type + "/issue")
        .header("Authorization", "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body)));
  }

  private JsonNode hrInbox(String token) throws Exception {
    return json.readTree(
        mvc.perform(get("/requests/hr-letters").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString());
  }

  private void doc(OffboardingDocType type, OffboardingDocStatus st) {
    OffboardingDocument d = new OffboardingDocument();
    d.setCaseId(caseA.getId());
    d.setType(type);
    d.setStatus(st);
    d.setSentByUserId(hrA.getId());
    docs.save(d);
  }

  private void verifyDoc(OffboardingDocType type) {
    OffboardingDocument d = docs.findByCaseIdAndType(caseA.getId(), type).orElseThrow();
    d.setStatus(OffboardingDocStatus.VERIFIED);
    docs.save(d);
  }

  private String company(String name, String code) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(role.name() + " " + email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private void team(String companyId, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    teams.save(t);
  }

  private Employee approvedEmployee(String companyId, String hrId, String code) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName(FULLNAME);
    e.setEmail(PERSONAL);
    e.setDesignation("Engineer");
    e.setDateOfJoining(LocalDate.parse("2023-04-03"));
    e.setMailAddress(MAILBOX);
    e.setPasswordHash(encoder.encode(PASSWORD));
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private OffboardingCase offboardingCase(String employeeId, String hrId) {
    OffboardingCase oc = new OffboardingCase();
    oc.setEmployeeId(employeeId);
    oc.setStatus(OffboardingStatus.APPROVED);
    oc.setReason("Resignation");
    oc.setLastWorkingDay(LocalDate.parse("2023-05-09"));
    oc.setInitiatedByUserId(hrId);
    return cases.save(oc);
  }

  private String tokenFor(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), UserRole.HR, u.getCompanyId(), null));
  }
}
