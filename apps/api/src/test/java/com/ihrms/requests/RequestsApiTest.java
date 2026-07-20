package com.ihrms.requests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.push.PushService;
import com.ihrms.storage.StorageService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HR/Accounts Requests — Accounts side (§8d): submit → route to the onboarding-HR's team Accountant →
 * pick-up → upload + resolve → download; owner cancel while submitted; the no-accountant 409; scope +
 * credentialed-employee gate; best-effort notifications. Storage is mocked so it runs with only a DB.
 * Gated on a local Postgres ({@code IHRMS_TEST_DB}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class RequestsApiTest {

  private static final byte[] FILE_BYTES = "a payslip pdf".getBytes(StandardCharsets.UTF_8);

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired JdbcTemplate jdbc;

  @MockBean StorageService storage;
  @SpyBean PushService push; // real by default; stubbed to fail in the best-effort test

  private String acme;
  private User accountant1;
  private User accountant2; // a different team's accountant (scope)
  private Employee emp1; // credentialed, onboarded by hr1 -> routes to accountant1
  private Employee emp2; // credentialed, same team (cross-employee download 403)
  private Employee empNoAccountant; // team has HR but no accountant -> 409
  private Employee uncredentialed; // OTP-only, no mailbox

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"request_documents\",\"document_requests\",\"teams\",\"employees\",\"users\",\"companies\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");

    User hr1 = user(acme, UserRole.HR, "hr1@acme");
    User manager1 = user(acme, UserRole.MANAGER, "mgr1@acme");
    accountant1 = user(acme, UserRole.ACCOUNTANT, "acct1@acme");
    team(acme, hr1.getId(), manager1.getId(), accountant1.getId());

    User hr2 = user(acme, UserRole.HR, "hr2@acme");
    accountant2 = user(acme, UserRole.ACCOUNTANT, "acct2@acme");
    team(acme, hr2.getId(), user(acme, UserRole.MANAGER, "mgr2@acme").getId(), accountant2.getId());

    User hr3 = user(acme, UserRole.HR, "hr3@acme");
    team(acme, hr3.getId(), null, null); // a team with NO accountant assigned

    emp1 = employee(acme, hr1, "arjun@acme");
    emp2 = employee(acme, hr1, "meera@acme");
    empNoAccountant = employee(acme, hr3, "sam@acme");
    uncredentialed = employee(acme, hr1, null);

    when(storage.buildKey(anyString(), anyString(), anyString(), anyString()))
        .thenReturn("companies/c/employees/e/requests/obj");
    when(storage.presignedPutUrl(anyString(), anyString(), anyInt()))
        .thenReturn("http://storage.local/put?sig=test");
    when(storage.presignedGetUrl(anyString(), anyInt()))
        .thenReturn("http://storage.local/get?sig=test");
    when(storage.getObjectBytes(any())).thenReturn(FILE_BYTES);
  }

  @Test
  void submitRoutesToTheTeamAccountantAndIsScoped() throws Exception {
    JsonNode created = submit(emp1, "PAYSLIP", "Jan-Mar 2026");
    assertThat(created.get("status").asText()).isEqualTo("SUBMITTED");

    // The onboarding-HR's team accountant (accountant1) sees it; accountant2 (other team) does not.
    JsonNode queue = teamQueue(accountant1, null);
    assertThat(queue.get("totalElements").asInt()).isEqualTo(1);
    JsonNode row = queue.get("content").get(0);
    assertThat(row.get("employeeName").asText()).isEqualTo(emp1.getFullName());
    assertThat(row.get("requestType").asText()).isEqualTo("PAYSLIP");
    assertThat(row.get("note").asText()).isEqualTo("Jan-Mar 2026");
    assertThat(row.get("teamName").asText()).isNotBlank();

    assertThat(teamQueue(accountant2, null).get("totalElements").asInt()).isZero();
    // Every action is audited with the company set.
    assertThat(auditCount("REQUEST_SUBMITTED")).isEqualTo(1);
  }

  @Test
  void aTeamWithNoAccountantRejectsTheSubmit() throws Exception {
    mvc.perform(
            post("/requests")
                .header("Authorization", "Bearer " + empToken(empNoAccountant))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("SALARY_CERTIFICATE", "please")))
        .andExpect(status().isConflict());
  }

  @Test
  void pickUpUploadResolveThenTheEmployeeDownloads() throws Exception {
    String id = submit(emp1, "PAYSLIP", "Q1").get("id").asText();

    // Pick up -> IN_PROGRESS.
    JsonNode picked = pickUp(accountant1, id);
    assertThat(picked.get("status").asText()).isEqualTo("IN_PROGRESS");
    assertThat(picked.get("pickedUpAt").asText()).isNotBlank();

    // Upload two payslip PDFs, then resolve -> RESOLVED with 2 docs + resolvedAt.
    String d1 = uploadUrl(accountant1, id, "payslip-jan.pdf");
    String d2 = uploadUrl(accountant1, id, "payslip-feb.pdf");
    JsonNode resolved = resolve(accountant1, id, List.of(d1, d2), "here are your payslips");
    assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
    assertThat(resolved.get("resolvedAt").asText()).isNotBlank();
    assertThat(resolved.get("documents")).hasSize(2);

    // The employee sees it RESOLVED with the 2 documents in their own history.
    JsonNode mine = myRequests(emp1);
    JsonNode row = rowOf(mine, id);
    assertThat(row.get("status").asText()).isEqualTo("RESOLVED");
    JsonNode docs = row.get("documents");
    assertThat(docs).hasSize(2);
    String docId = docs.get(0).get("id").asText();

    // The employee downloads their own document (presigned GET).
    JsonNode dl = download(empToken(emp1), id, docId).get("url");
    assertThat(dl.asText()).startsWith("http");
    // The routed accountant may also download it.
    downloadOk(token(accountant1), id, docId);

    assertThat(auditCount("REQUEST_RESOLVED")).isEqualTo(1);
    assertThat(auditCount("REQUEST_DOCUMENT_UPLOADED")).isEqualTo(2);
    assertThat(auditCount("REQUEST_DOCUMENT_DOWNLOADED")).isEqualTo(2);
  }

  @Test
  void crossScopeDownloadAndActionsAreDenied() throws Exception {
    String id = submit(emp1, "PAYSLIP", "Q1").get("id").asText();
    pickUp(accountant1, id);
    String d1 = uploadUrl(accountant1, id, "payslip.pdf");
    resolve(accountant1, id, List.of(d1), null);
    String docId = rowOf(myRequests(emp1), id).get("documents").get(0).get("id").asText();

    // A DIFFERENT employee cannot download it.
    mvc.perform(get(downloadPath(id, docId)).header("Authorization", "Bearer " + empToken(emp2)))
        .andExpect(status().isForbidden());
    // An accountant from ANOTHER team cannot download / act on it.
    mvc.perform(get(downloadPath(id, docId)).header("Authorization", "Bearer " + token(accountant2)))
        .andExpect(status().isForbidden());
    mvc.perform(post("/requests/team/" + id + "/pick-up").header("Authorization", "Bearer " + token(accountant2)))
        .andExpect(status().isForbidden());
  }

  @Test
  void cancelRulesAndCredentialGate() throws Exception {
    // Cancel while SUBMITTED -> CANCELLED.
    String a = submit(emp1, "FORM16", "FY25-26").get("id").asText();
    mvc.perform(post("/requests/" + a + "/cancel").header("Authorization", "Bearer " + empToken(emp1)))
        .andExpect(status().isOk());
    assertThat(rowOf(myRequests(emp1), a).get("status").asText()).isEqualTo("CANCELLED");

    // Once IN_PROGRESS it can no longer be cancelled.
    String b = submit(emp1, "TAX_DOCUMENT", "2026").get("id").asText();
    pickUp(accountant1, b);
    mvc.perform(post("/requests/" + b + "/cancel").header("Authorization", "Bearer " + empToken(emp1)))
        .andExpect(status().isConflict());

    // OTP-only employee (no mailbox) -> 403 everywhere; a staff non-employee -> 403 on submit.
    String otp = empToken(uncredentialed);
    mvc.perform(
            post("/requests")
                .header("Authorization", "Bearer " + otp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("PAYSLIP", "x")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/requests/me").header("Authorization", "Bearer " + otp))
        .andExpect(status().isForbidden());
    mvc.perform(get("/requests/team").header("Authorization", "Bearer " + otp))
        .andExpect(status().isForbidden());
  }

  @Test
  void aPushFailureDoesNotBreakTheRequestAction() throws Exception {
    // Simulate the best-effort OS push failing: it must be swallowed, the request still created.
    Mockito.doThrow(new RuntimeException("push transport down"))
        .when(push)
        .sendToPrincipal(any(), any(), any(), any());

    JsonNode created = submit(emp1, "PAYSLIP", "trip");
    assertThat(created.get("status").asText()).isEqualTo("SUBMITTED");
    assertThat(teamQueue(accountant1, null).get("totalElements").asInt()).isEqualTo(1);

    // A resolve also survives a push failure (the employee notification is best-effort too).
    String id = created.get("id").asText();
    pickUp(accountant1, id);
    String d1 = uploadUrl(accountant1, id, "payslip.pdf");
    assertThat(resolve(accountant1, id, List.of(d1), null).get("status").asText()).isEqualTo("RESOLVED");
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode submit(Employee e, String type, String note) throws Exception {
    return json.readTree(
        mvc.perform(
                post("/requests")
                    .header("Authorization", "Bearer " + empToken(e))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(type, note)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode myRequests(Employee e) throws Exception {
    return json.readTree(
        mvc.perform(get("/requests/me").header("Authorization", "Bearer " + empToken(e)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode teamQueue(User accountant, String statusFilter) throws Exception {
    var req = get("/requests/team").header("Authorization", "Bearer " + token(accountant));
    if (statusFilter != null) req = req.param("status", statusFilter);
    return json.readTree(
        mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
  }

  private JsonNode pickUp(User accountant, String id) throws Exception {
    return json.readTree(
        mvc.perform(
                post("/requests/team/" + id + "/pick-up")
                    .header("Authorization", "Bearer " + token(accountant)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private String uploadUrl(User accountant, String id, String fileName) throws Exception {
    JsonNode res =
        json.readTree(
            mvc.perform(
                    post("/requests/team/" + id + "/documents/upload-url")
                        .header("Authorization", "Bearer " + token(accountant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            json.writeValueAsString(
                                Map.of(
                                    "fileName", fileName,
                                    "contentType", "application/pdf",
                                    "sizeBytes", 2048))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());
    return res.get("documentId").asText();
  }

  private JsonNode resolve(User accountant, String id, List<String> docIds, String note)
      throws Exception {
    var payload = new java.util.HashMap<String, Object>();
    payload.put("documentIds", docIds);
    if (note != null) payload.put("note", note);
    return json.readTree(
        mvc.perform(
                post("/requests/team/" + id + "/resolve")
                    .header("Authorization", "Bearer " + token(accountant))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(payload)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode download(String bearer, String id, String docId) throws Exception {
    return json.readTree(
        mvc.perform(get(downloadPath(id, docId)).header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private void downloadOk(String bearer, String id, String docId) throws Exception {
    mvc.perform(get(downloadPath(id, docId)).header("Authorization", "Bearer " + bearer))
        .andExpect(status().isOk());
  }

  private String downloadPath(String id, String docId) {
    return "/requests/" + id + "/documents/" + docId + "/download";
  }

  private JsonNode rowOf(JsonNode page, String id) {
    for (JsonNode row : page.get("content")) {
      if (row.get("id").asText().equals(id)) {
        return row;
      }
    }
    throw new AssertionError("request " + id + " not found in page");
  }

  private int auditCount(String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM \"audit_logs\" WHERE \"action\" = ? AND \"companyId\" IS NOT NULL",
        Integer.class,
        action);
  }

  private String body(String type, String note) throws Exception {
    return json.writeValueAsString(Map.of("requestType", type, "note", note));
  }

  private String company(String code, String mailDomain) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    c.setMailDomain(mailDomain);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Team team(String companyId, String hrId, String managerId, String accountantId) {
    Team t = new Team();
    t.setName("Team " + hrId);
    t.setCompanyId(companyId);
    t.setHrUserId(hrId);
    t.setManagerUserId(managerId);
    t.setAccountantUserId(accountantId);
    return teams.save(t);
  }

  private Employee employee(String companyId, User hrUser, String mailAddress) {
    Employee e = new Employee();
    e.setFullName("Emp " + (mailAddress == null ? "nomail" : mailAddress.split("@")[0]));
    e.setEmail("personal-" + UUID.randomUUID() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrUser.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    e.setEmployeeCode("ACME-EMP-" + UUID.randomUUID());
    if (mailAddress != null) {
      e.setMailLocalPart(mailAddress.split("@")[0]);
      e.setMailAddress(mailAddress);
      e.setPasswordHash("hashed");
    }
    return employees.save(e);
  }

  private String empToken(Employee e) {
    return tokens.issueAccess(
        new IhrmsPrincipal.Employee(
            e.getId(),
            e.getEmployeeCode(),
            e.getEmail(),
            e.getCompanyId(),
            e.getFullName(),
            e.getMailAddress()));
  }

  private String token(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(
            u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), null));
  }
}
