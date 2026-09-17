package com.ihrms.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * EXISTING-employee onboarding (ARCHITECTURE.md §3.2, records-only): HR/SA create the record from Form 2 —
 * including the employee ID + official email the person ALREADY has — with NO offer, NO invite token and NO
 * email; HR enters Forms 1/3/4 + a scanned signature via {@code /employees/{id}/onboarding} and APPROVES
 * directly, which KEEPS the entered ID (never minted) and still writes the ApprovalRequest + manager
 * notification. Storage is mocked (DB-only run, like OnboardingApiTest).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ExistingEmployeeApiTest {

  private static final byte[] FILE_BYTES = "hello existing employee".getBytes(StandardCharsets.UTF_8);
  private static final String SIGNATURE = "data:image/png;base64,aGVsbG8gc2lnbmF0dXJl";
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired TeamRepository teams;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @MockBean StorageService storage;

  private String companyId;
  private User hr;
  private User manager;
  private String hrToken;
  private String saToken;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\",\"employee_offers\",\"employee_invite_tokens\" RESTART IDENTITY CASCADE");
    companyId = company("ACME");
    hr = staff(companyId, UserRole.HR, "hr@acme.test");
    manager = staff(companyId, UserRole.MANAGER, "mgr@acme.test");
    team(companyId, "Engineering", hr.getId(), manager.getId());
    hrToken = tokenFor(hr, UserRole.HR);
    User sa = staff(null, UserRole.SUPER_ADMIN, "sa@platform.test");
    saToken = tokenFor(sa, UserRole.SUPER_ADMIN);

    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("companies/c/employees/e/obj");
    when(storage.presignedPutUrl(anyString(), anyString(), anyInt()))
        .thenReturn("http://storage.local/put?sig=test");
    when(storage.presignedGetUrl(anyString(), anyInt()))
        .thenReturn("http://storage.local/get?sig=test");
    when(storage.getObjectBytes(any())).thenReturn(FILE_BYTES);
  }

  // --- creation (records-only: no offer, no invite, no email) -----------------

  @Test
  void hrCreatesRecordOnlyNoOfferNoInviteNoEmail(CapturedOutput output) throws Exception {
    MvcResult r =
        mvc.perform(createExisting("Priya Raman", "priya@personal.test", "DI-1042"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.loginUrl").value(org.hamcrest.Matchers.nullValue())) // nothing was sent
            .andReturn();
    JsonNode employee = json.readTree(r.getResponse().getContentAsString()).get("employee");
    String id = employee.get("id").asText();
    assertThat(employee.get("status").asText()).isEqualTo("IN_PROGRESS"); // never INVITED
    assertThat(employee.get("onboardingType").asText()).isEqualTo("EXISTING_EMPLOYEE");
    assertThat(employee.get("employeeCode").isNull()).isTrue(); // kept in Form 2 until approval (§6)

    // Records only: no offer row, no invite token, and not one email of any kind.
    assertThat(count("employee_offers")).isZero();
    assertThat(count("employee_invite_tokens")).isZero();
    assertThat(output.getOut()).doesNotContain("[DEV SELECTION]").doesNotContain("[DEV WELCOME]");

    // The entered identity lives on the record view's Form 2 before approval.
    mvc.perform(get("/employees/" + id + "/record").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboardingType").value("EXISTING_EMPLOYEE"))
        .andExpect(jsonPath("$.employeeCode").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.form2.employeeId").value("DI-1042"))
        .andExpect(jsonPath("$.form2.officialEmail").value("priya.raman@acme.example"));

    // Audit carries the mode + the entered ID.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT metadata::text AS m FROM \"audit_logs\" WHERE action = 'EMPLOYEE_ONBOARDED'");
    assertThat((String) row.get("m")).contains("EXISTING_EMPLOYEE").contains("DI-1042");
  }

  @Test
  void validatesIdentityAndJoiningDate() throws Exception {
    expectBad(createExistingBody("A One", "a1@p.test", null), "Employee ID is required");
    expectBad(createExistingBody("A Two", "a2@p.test", "DI 1042!"), "Employee ID may use");
    expectBad(createExistingBody("A Three", "a3@p.test", "ACME-EMP-000123"), "reserved");
    Map<String, Object> noOfficial = createExistingBody("A Four", "a4@p.test", "DI-4");
    ((Map<String, Object>) noOfficial.get("form2")).remove("officialEmail");
    expectBad(noOfficial, "Official email is required");
    Map<String, Object> badOfficial = createExistingBody("A Five", "a5@p.test", "DI-5");
    ((Map<String, Object>) badOfficial.get("form2")).put("officialEmail", "not-an-email");
    expectBad(badOfficial, "valid official email");
    Map<String, Object> future = createExistingBody("A Six", "a6@p.test", "DI-6");
    ((Map<String, Object>) future.get("form2"))
        .put("dateOfJoining", LocalDate.now(IST).plusDays(1).toString());
    expectBad(future, "Date of joining cannot be in the future");
    // Today (IST) is the latest allowed — accepted.
    Map<String, Object> today = createExistingBody("A Seven", "a7@p.test", "DI-7");
    ((Map<String, Object>) today.get("form2")).put("dateOfJoining", LocalDate.now(IST).toString());
    mvc.perform(postExisting(today)).andExpect(status().isCreated());
  }

  @Test
  void enteredIdIsGloballyUniqueCaseInsensitive() throws Exception {
    mvc.perform(createExisting("First Holder", "first@p.test", "DI-1042"))
        .andExpect(status().isCreated());
    mvc.perform(createExisting("Second Holder", "second@p.test", "di-1042"))
        .andExpect(status().isConflict());
    // And the personal email stays globally unique too.
    mvc.perform(createExisting("Dup Email", "first@p.test", "DI-9999"))
        .andExpect(status().isConflict());
  }

  @Test
  void newHireStillRequiresOfferAndIgnoresAnEnteredId() throws Exception {
    // The new-hire request without an offer stays a 400 (contract unchanged).
    mvc.perform(
            post("/employees")
                .header("Authorization", "Bearer " + hrToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("form2", form2Body("No Offer", "no@p.test", "X-1")))))
        .andExpect(status().isBadRequest());
    // A new hire may carry form2.employeeId in the payload — it is ignored, the code is minted on approval.
    MvcResult r =
        mvc.perform(
                post("/employees")
                    .header("Authorization", "Bearer " + hrToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "form2", form2Body("New Hire", "nh@p.test", "SHOULD-IGNORE"),
                                "offer", Map.of("salary", "5,40,000 Per Annum")))))
            .andExpect(status().isCreated())
            .andReturn();
    JsonNode employee = json.readTree(r.getResponse().getContentAsString()).get("employee");
    assertThat(employee.get("employeeCode").isNull()).isTrue();
    assertThat(employee.get("onboardingType").asText()).isEqualTo("NEW_HIRE");
    Integer stored =
        jdbc.queryForObject(
            "SELECT count(*) FROM \"form2_info\" WHERE data->>'employeeId' IS NOT NULL", Integer.class);
    assertThat(stored).isZero();
    // And an existing employee may not reuse an ID equal to a minted-style code either (covered above).
  }

  @Test
  void superAdminVariantAttachesToTheTeamsHr() throws Exception {
    Team team = teams.findByCompanyIdAndHrUserId(companyId, hr.getId()).orElseThrow();
    // Wrong company → 400.
    String otherCompany = company("OTHR");
    mvc.perform(saCreateExisting(otherCompany, team.getId(), "Sa One", "sa1@p.test", "DI-77"))
        .andExpect(status().isBadRequest());
    // Team without an HR → 400.
    Team hrless = new Team();
    hrless.setCompanyId(companyId);
    hrless.setName("Ops");
    teams.save(hrless);
    mvc.perform(saCreateExisting(companyId, hrless.getId(), "Sa Two", "sa2@p.test", "DI-78"))
        .andExpect(status().isBadRequest());
    // Happy path: created IN_PROGRESS, attached to the team's HR, no login link.
    MvcResult r =
        mvc.perform(saCreateExisting(companyId, team.getId(), "Sa Three", "sa3@p.test", "DI-79"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.loginUrl").value(org.hamcrest.Matchers.nullValue()))
            .andReturn();
    String id = json.readTree(r.getResponse().getContentAsString()).get("employee").get("id").asText();
    String hrId =
        jdbc.queryForObject(
            "SELECT \"onboardingHrId\" FROM \"employees\" WHERE id = ?", String.class, id);
    assertThat(hrId).isEqualTo(hr.getId());
  }

  @Test
  void noInviteToResendAndForm2StaysEditableUntilApproved(CapturedOutput output) throws Exception {
    String id = create("Meena Krishnan", "meena@p.test", "DI-2001");

    mvc.perform(post("/employees/" + id + "/invite/resend").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isConflict());

    // Editable while IN_PROGRESS — including an email change, which re-invites NOBODY (no email exists).
    Map<String, Object> edit = form2Body("Meena Krishnan", "meena.new@p.test", "DI-2001");
    mvc.perform(patchForm2(id, edit)).andExpect(status().isOk());
    assertThat(output.getOut()).doesNotContain("[DEV SELECTION]");
    Map<String, Object> reaudit =
        jdbc.queryForMap(
            "SELECT metadata::text AS m FROM \"audit_logs\" WHERE action = 'FORM2_UPDATED'");
    assertThat((String) reaudit.get("m")).contains("\"reinvited\": false");
    assertThat(count("audit_logs WHERE action = 'EMPLOYEE_REINVITED'")).isZero();

    // The entered-ID rules hold on edit too.
    String other = create("Other Person", "other@p.test", "DI-2002");
    Map<String, Object> stealId = form2Body("Meena Krishnan", "meena.new@p.test", "di-2002");
    mvc.perform(patchForm2(id, stealId)).andExpect(status().isConflict());
    Map<String, Object> future = form2Body("Meena Krishnan", "meena.new@p.test", "DI-2001");
    future.put("dateOfJoining", LocalDate.now(IST).plusDays(3).toString());
    mvc.perform(patchForm2(id, future)).andExpect(status().isBadRequest());
    // Keeping its own ID (case changed) is fine.
    mvc.perform(patchForm2(id, form2Body("Meena Krishnan", "meena.new@p.test", "di-2001")))
        .andExpect(status().isOk());
    assertThat(other).isNotBlank();
  }

  // --- the HR entry surface + approve -----------------------------------------

  @Test
  void hrEntersTheRecordAndApprovesKeepingTheId(CapturedOutput output) throws Exception {
    String id = create("Ravi Kumar", "ravi@p.test", "DI-0877");

    // Dashboard mirrors the employee's own shape: no offer, type present.
    mvc.perform(entryGet(id, ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.onboardingType").value("EXISTING_EMPLOYEE"))
        .andExpect(jsonPath("$.offer").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

    // Approving an empty record names the first missing item.
    mvc.perform(post("/employees/" + id + "/onboarding/approve").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Complete the record")));

    // Form 1, Form 3, the three required documents, and the scanned signature — all as HR.
    mvc.perform(entryPut(id, "/form1", form1Body())).andExpect(status().isOk());
    mvc.perform(
            entryPut(
                id,
                "/form3",
                Map.of("entries", List.of(Map.of("companyName", "Prior Employer Pvt Ltd")))))
        .andExpect(status().isOk());
    for (String docType : List.of("AADHAAR", "PAN", "ITR")) {
      MvcResult presign =
          mvc.perform(
                  post("/employees/" + id + "/onboarding/documents")
                      .header("Authorization", "Bearer " + hrToken)
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(
                          json.writeValueAsString(
                              Map.of(
                                  "docType", docType,
                                  "fileName", docType.toLowerCase() + ".pdf",
                                  "mimeType", "application/pdf",
                                  "sizeBytes", 1024))))
              .andExpect(status().isCreated())
              .andReturn();
      String documentId =
          json.readTree(presign.getResponse().getContentAsString()).get("documentId").asText();
      mvc.perform(
              post("/employees/" + id + "/onboarding/documents/" + documentId + "/confirm")
                  .header("Authorization", "Bearer " + hrToken))
          .andExpect(status().isCreated())
          .andExpect(jsonPath("$.status").value("UPLOADED"));
    }
    mvc.perform(entryPut(id, "/signature", Map.of("imageDataUrl", SIGNATURE, "type", "DRAWN")))
        .andExpect(status().isOk());

    // Approve: keeps the entered ID, verifies everything, notifies the manager — and emails NOBODY.
    mvc.perform(post("/employees/" + id + "/onboarding/approve").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.employeeCode").value("DI-0877"))
        .andExpect(jsonPath("$.status").value("APPROVED"))
        .andExpect(jsonPath("$.teamName").value("Engineering"))
        .andExpect(jsonPath("$.managerName").value(manager.getName()));
    assertThat(output.getOut()).doesNotContain("[DEV WELCOME]").doesNotContain("[DEV SELECTION]");

    assertThat(
            jdbc.queryForObject(
                "SELECT \"employeeCode\" FROM \"employees\" WHERE id = ?", String.class, id))
        .isEqualTo("DI-0877");
    assertThat(count("approval_requests WHERE status = 'APPROVED'")).isEqualTo(1);
    assertThat(count("notifications WHERE type = 'EMPLOYEE_APPROVED'")).isEqualTo(1);
    assertThat(count("audit_logs WHERE action = 'HR_APPROVED'")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM \"form1_personal\" WHERE \"employeeId\" = ?", String.class, id))
        .isEqualTo("VERIFIED");
    Integer unverifiedDocs =
        jdbc.queryForObject(
            "SELECT count(*) FROM \"documents\" WHERE \"employeeId\" = ? AND status <> 'VERIFIED'",
            Integer.class,
            id);
    assertThat(unverifiedDocs).isZero();

    // Locked after approval: writes 409, reads keep working, Form 2 locks too.
    mvc.perform(entryPut(id, "/form1", form1Body())).andExpect(status().isConflict());
    mvc.perform(post("/employees/" + id + "/onboarding/approve").header("Authorization", "Bearer " + hrToken))
        .andExpect(status().isConflict());
    mvc.perform(entryGet(id, "")).andExpect(status().isOk());
    mvc.perform(patchForm2(id, form2Body("Ravi Kumar", "ravi@p.test", "DI-0877")))
        .andExpect(status().isConflict());
  }

  @Test
  void entrySurfaceIsExistingOnlyAndHrScoped() throws Exception {
    // A NEW hire's record is never HR-entered.
    MvcResult r =
        mvc.perform(
                post("/employees")
                    .header("Authorization", "Bearer " + hrToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        json.writeValueAsString(
                            Map.of(
                                "form2", form2Body("New Hire", "nh2@p.test", null),
                                "offer", Map.of("salary", "5,00,000 Per Annum")))))
            .andExpect(status().isCreated())
            .andReturn();
    String newHireId =
        json.readTree(r.getResponse().getContentAsString()).get("employee").get("id").asText();
    mvc.perform(entryGet(newHireId, "")).andExpect(status().isConflict());

    // Another company's HR sees a 404 (existence hidden).
    String otherCompany = company("OTHR");
    User otherHr = staff(otherCompany, UserRole.HR, "hr@othr.test");
    team(otherCompany, "Ops", otherHr.getId(), null);
    String id = create("Scoped Person", "scoped@p.test", "DI-3001");
    mvc.perform(
            get("/employees/" + id + "/onboarding")
                .header("Authorization", "Bearer " + tokenFor(otherHr, UserRole.HR)))
        .andExpect(status().isNotFound());
  }

  // --- fixtures ---------------------------------------------------------------

  private MockHttpServletRequestBuilder createExisting(String fullName, String email, String employeeId)
      throws Exception {
    return postExisting(createExistingBody(fullName, email, employeeId));
  }

  private MockHttpServletRequestBuilder postExisting(Map<String, Object> body) throws Exception {
    return post("/employees/existing")
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private Map<String, Object> createExistingBody(String fullName, String email, String employeeId) {
    return new HashMap<>(Map.of("form2", form2Body(fullName, email, employeeId)));
  }

  private MockHttpServletRequestBuilder saCreateExisting(
      String companyId, String teamId, String fullName, String email, String employeeId)
      throws Exception {
    return post("/companies/" + companyId + "/employees/existing")
        .header("Authorization", "Bearer " + saToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            json.writeValueAsString(
                Map.of("teamId", teamId, "form2", form2Body(fullName, email, employeeId))));
  }

  /** A Form-2 body for the EXISTING mode: a past joining date + the identity the person already has. */
  private static Map<String, Object> form2Body(String fullName, String personalEmail, String employeeId) {
    Map<String, Object> m = new HashMap<>();
    m.put("fullName", fullName);
    m.put("personalEmail", personalEmail);
    m.put("designation", "Senior Accountant");
    m.put("dateOfJoining", "2019-06-01");
    m.put("officialEmail", "priya.raman@acme.example");
    if (employeeId != null) {
      m.put("employeeId", employeeId);
    }
    return m;
  }

  private String create(String fullName, String email, String employeeId) throws Exception {
    MvcResult r =
        mvc.perform(createExisting(fullName, email, employeeId))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(r.getResponse().getContentAsString()).get("employee").get("id").asText();
  }

  private void expectBad(Map<String, Object> body, String messagePart) throws Exception {
    mvc.perform(postExisting(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(messagePart)));
  }

  private MockHttpServletRequestBuilder entryGet(String id, String path) throws Exception {
    return get("/employees/" + id + "/onboarding" + path).header("Authorization", "Bearer " + hrToken);
  }

  private MockHttpServletRequestBuilder entryPut(String id, String path, Map<String, Object> body)
      throws Exception {
    return put("/employees/" + id + "/onboarding" + path)
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private MockHttpServletRequestBuilder patchForm2(String employeeId, Map<String, Object> body)
      throws Exception {
    return patch("/employees/" + employeeId + "/form2")
        .header("Authorization", "Bearer " + hrToken)
        .contentType(MediaType.APPLICATION_JSON)
        .content(json.writeValueAsString(body));
  }

  private Map<String, Object> form1Body() {
    return Map.of(
        "name", "Ravi Kumar",
        "dateOfBirth", "1988-03-12",
        "email", "ravi@p.test",
        "mobile", "5551234",
        "characterReferences",
            List.of(
                Map.of("name", "Ref One", "phone", "5550001"),
                Map.of("name", "Ref Two", "phone", "5550002")));
  }

  private int count(String tableAndWhere) {
    return jdbc.queryForObject("SELECT count(*) FROM " + qualify(tableAndWhere), Integer.class);
  }

  private static String qualify(String tableAndWhere) {
    int space = tableAndWhere.indexOf(' ');
    if (space < 0) {
      return '"' + tableAndWhere + '"';
    }
    return '"' + tableAndWhere.substring(0, space) + '"' + tableAndWhere.substring(space);
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User staff(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private void team(String companyId, String name, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName(name);
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    teams.save(t);
  }

  private String tokenFor(User user, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(
            user.getId(), user.getEmail(), user.getName(), role, user.getCompanyId(), null));
  }
}
