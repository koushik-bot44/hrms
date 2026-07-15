package com.ihrms.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.mail.InternalMailService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Leave requests (§8b): submit → route to the onboarding-HR's team Manager → decide; owner cancel while
 * pending; scope + credentialed-employee gate. Gated on a local Postgres ({@code IHRMS_TEST_DB}).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class LeaveApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired NotificationRepository notifications;
  @Autowired JdbcTemplate jdbc;
  @SpyBean InternalMailService internalMail; // real by default; stubbed to fail in the best-effort test

  private String acme;
  private User hr1;
  private User manager1;
  private User manager2; // a different team's manager (scope)
  private Employee emp1; // credentialed, onboarded by hr1 -> routes to manager1
  private Employee uncredentialed; // OTP-only, no mailbox

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"leave_requests\",\"notifications\",\"messages\",\"message_recipients\",\"message_attachments\",\"teams\",\"employees\",\"users\",\"companies\",\"audit_logs\" RESTART IDENTITY CASCADE");
    acme = company("ACME", "acme");
    hr1 = user(acme, UserRole.HR, "hr1@acme");
    manager1 = user(acme, UserRole.MANAGER, "mgr1@acme");
    team(acme, hr1.getId(), manager1.getId());

    User hr2 = user(acme, UserRole.HR, "hr2@acme");
    manager2 = user(acme, UserRole.MANAGER, "mgr2@acme");
    team(acme, hr2.getId(), manager2.getId());

    emp1 = employee(acme, hr1, "arjun@acme");
    uncredentialed = employee(acme, hr1, null);
  }

  @Test
  void submitRoutesToTheOnboardingHrTeamManagerAndNotifiesHim() throws Exception {
    JsonNode created = submit(emp1, "2026-08-01", "2026-08-03", "CASUAL", "family trip");
    assertThat(created.get("status").asText()).isEqualTo("PENDING");

    // The onboarding HR's team manager (manager1) sees it; manager2 (other team) does not.
    JsonNode mine = teamQueue(manager1);
    assertThat(mine.get("totalElements").asInt()).isEqualTo(1);
    JsonNode row = mine.get("content").get(0);
    assertThat(row.get("status").asText()).isEqualTo("PENDING");
    assertThat(row.get("employeeName").asText()).isEqualTo(emp1.getFullName());
    assertThat(row.get("leaveType").asText()).isEqualTo("CASUAL");

    assertThat(teamQueue(manager2).get("totalElements").asInt()).isZero();

    // Manager1 got a real bell entry.
    assertThat(
            notifications.findByRecipientUserIdOrderByCreatedAtDesc(manager1.getId()).stream()
                .anyMatch(n -> n.getType() == NotificationType.LEAVE_REQUESTED))
        .isTrue();
  }

  @Test
  void managerApprovesAndRejects_rejectRequiresANote() throws Exception {
    String a = submit(emp1, "2026-08-01", "2026-08-02", "SICK", "fever").get("id").asText();
    String b = submit(emp1, "2026-08-10", "2026-08-11", "UNPAID", "personal").get("id").asText();

    // Approve A.
    decide("approve", a, manager1, null).andExpect(status().isOk());
    // The employee sees A approved in their own history.
    assertThat(statusOf(myHistory(emp1), a)).isEqualTo("APPROVED");

    // Reject B with no note -> 400; with a note -> REJECTED.
    decide("reject", b, manager1, Map.of()).andExpect(status().isBadRequest());
    decide("reject", b, manager1, Map.of("note", "clashes with release")).andExpect(status().isOk());
    assertThat(statusOf(myHistory(emp1), b)).isEqualTo("REJECTED");
  }

  @Test
  void employeeCancelsPendingButNotAfterDecision() throws Exception {
    String pending = submit(emp1, "2026-09-01", "2026-09-02", "CASUAL", "x").get("id").asText();
    mvc.perform(post("/leave/" + pending + "/cancel").header("Authorization", "Bearer " + empToken(emp1)))
        .andExpect(status().isOk());
    assertThat(statusOf(myHistory(emp1), pending)).isEqualTo("CANCELLED");

    // A decided request can no longer be cancelled.
    String decided = submit(emp1, "2026-09-10", "2026-09-11", "CASUAL", "y").get("id").asText();
    decide("approve", decided, manager1, null).andExpect(status().isOk());
    mvc.perform(post("/leave/" + decided + "/cancel").header("Authorization", "Bearer " + empToken(emp1)))
        .andExpect(status().isConflict());
  }

  @Test
  void validationAndCredentialGate() throws Exception {
    // endDate < startDate -> 400.
    mvc.perform(
            post("/leave")
                .header("Authorization", "Bearer " + empToken(emp1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("2026-08-05", "2026-08-01", "CASUAL", "backwards")))
        .andExpect(status().isBadRequest());

    // OTP-only employee (no mailbox) -> 403 everywhere.
    String otp = empToken(uncredentialed);
    mvc.perform(
            post("/leave")
                .header("Authorization", "Bearer " + otp)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("2026-08-01", "2026-08-02", "CASUAL", "z")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/leave/me").header("Authorization", "Bearer " + otp))
        .andExpect(status().isForbidden());

    // A staff user is not an employee -> 403 on submit; and non-managers can't reach /leave/team.
    mvc.perform(
            post("/leave")
                .header("Authorization", "Bearer " + token(hr1))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body("2026-08-01", "2026-08-02", "CASUAL", "z")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/leave/team").header("Authorization", "Bearer " + token(hr1)))
        .andExpect(status().isForbidden());
  }

  @Test
  void aManagerWhoIsNotTheApproverCannotDecide() throws Exception {
    String id = submit(emp1, "2026-08-01", "2026-08-02", "CASUAL", "trip").get("id").asText();
    // manager2 is a different team's manager — not the resolved approver.
    decide("approve", id, manager2, null).andExpect(status().isForbidden());
    decide("reject", id, manager2, Map.of("note", "no")).andExpect(status().isForbidden());
    // Still pending.
    assertThat(statusOf(myHistory(emp1), id)).isEqualTo("PENDING");
  }

  @Test
  void employeeSeesOnlyTheirOwnRequests() throws Exception {
    submit(emp1, "2026-08-01", "2026-08-02", "CASUAL", "mine").get("id").asText();
    Employee emp2 = employee(acme, hr1, "meera@acme");
    submit(emp2, "2026-08-03", "2026-08-04", "SICK", "theirs");

    JsonNode mine = myHistory(emp1);
    assertThat(mine.get("totalElements").asInt()).isEqualTo(1);
    assertThat(mine.get("content").get(0).get("reason").asText()).isEqualTo("mine");
  }

  @Test
  void submitAlsoSendsARepliableInternalMailToTheManager() throws Exception {
    submit(emp1, "2026-08-01", "2026-08-03", "CASUAL", "family trip");

    // Lands in the Manager's inbox and the employee's Sent as an ordinary thread (§8/§8b).
    String subject = "Leave request: " + emp1.getFullName() + " (CASUAL)";
    JsonNode mgrInbox = inbox(token(manager1));
    assertThat(threadSubjects(mgrInbox)).containsExactly(subject);
    JsonNode empSent = sent(empToken(emp1));
    assertThat(threadSubjects(empSent)).containsExactly(subject);
    assertThat(empSent.get("content").get(0).get("snippet").asText())
        .contains("1 Aug 2026").contains("3 Aug 2026").contains("family trip");

    // It went through the real send path, so it is audited MAIL_SENT — on top of LEAVE_REQUESTED.
    assertThat(auditCount("MAIL_SENT")).isEqualTo(1);
    assertThat(auditCount("LEAVE_REQUESTED")).isEqualTo(1);
    // The other team's manager is not a party to it.
    assertThat(threadSubjects(inbox(token(manager2)))).isEmpty();
  }

  @Test
  void aDecisionSendsAnInternalMailBackToTheEmployee() throws Exception {
    String approved = submit(emp1, "2026-08-01", "2026-08-02", "SICK", "fever").get("id").asText();
    decide("approve", approved, manager1, null).andExpect(status().isOk());

    JsonNode empInbox = inbox(empToken(emp1));
    assertThat(threadSubjects(empInbox)).contains("Leave request approved");
    assertThat(threadSubjects(sent(token(manager1)))).contains("Leave request approved");

    String rejected = submit(emp1, "2026-08-10", "2026-08-11", "UNPAID", "personal").get("id").asText();
    decide("reject", rejected, manager1, Map.of("note", "clashes with release"))
        .andExpect(status().isOk());
    JsonNode rejThread = threadWithSubject(inbox(empToken(emp1)), "Leave request rejected");
    assertThat(rejThread).isNotNull();
    assertThat(rejThread.get("snippet").asText()).contains("clashes with release");
  }

  @Test
  void aMailFailureDoesNotBreakTheLeaveActionOrItsInAppNotifications() throws Exception {
    // Simulate the courtesy mail failing (transport error / not permitted): it must be swallowed.
    Mockito.doThrow(new RuntimeException("mail transport down"))
        .when(internalMail)
        .send(any(), any(), any());

    JsonNode created = submit(emp1, "2026-08-01", "2026-08-02", "CASUAL", "trip");
    assertThat(created.get("status").asText()).isEqualTo("PENDING");

    // The leave still persisted, the Manager still got the in-app bell, and no mail leaked.
    assertThat(teamQueue(manager1).get("totalElements").asInt()).isEqualTo(1);
    assertThat(
            notifications.findByRecipientUserIdOrderByCreatedAtDesc(manager1.getId()).stream()
                .anyMatch(n -> n.getType() == NotificationType.LEAVE_REQUESTED))
        .isTrue();
    assertThat(threadSubjects(inbox(token(manager1)))).isEmpty();

    // A decision also survives a mail failure.
    String id = created.get("id").asText();
    decide("approve", id, manager1, null).andExpect(status().isOk());
    assertThat(statusOf(myHistory(emp1), id)).isEqualTo("APPROVED");
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode inbox(String bearer) throws Exception {
    return threadPage(get("/mail/inbox"), bearer);
  }

  private JsonNode sent(String bearer) throws Exception {
    return threadPage(get("/mail/sent"), bearer);
  }

  private JsonNode threadPage(
      org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String bearer)
      throws Exception {
    return json.readTree(
        mvc.perform(req.header("Authorization", "Bearer " + bearer))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private java.util.List<String> threadSubjects(JsonNode page) {
    java.util.List<String> out = new java.util.ArrayList<>();
    for (JsonNode t : page.get("content")) {
      out.add(t.get("subject").asText());
    }
    return out;
  }

  private JsonNode threadWithSubject(JsonNode page, String subject) {
    for (JsonNode t : page.get("content")) {
      if (t.get("subject").asText().equals(subject)) {
        return t;
      }
    }
    return null;
  }

  private int auditCount(String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM \"audit_logs\" WHERE \"action\" = ?", Integer.class, action);
  }

  private JsonNode submit(Employee e, String start, String end, String type, String reason)
      throws Exception {
    return json.readTree(
        mvc.perform(
                post("/leave")
                    .header("Authorization", "Bearer " + empToken(e))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(start, end, type, reason)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode myHistory(Employee e) throws Exception {
    return json.readTree(
        mvc.perform(get("/leave/me").header("Authorization", "Bearer " + empToken(e)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private JsonNode teamQueue(User manager) throws Exception {
    return json.readTree(
        mvc.perform(get("/leave/team").header("Authorization", "Bearer " + token(manager)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private ResultActions decide(String action, String id, User manager, Map<String, String> note)
      throws Exception {
    var req =
        post("/leave/team/" + id + "/" + action).header("Authorization", "Bearer " + token(manager));
    if (note != null) {
      req = req.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(note));
    }
    return mvc.perform(req);
  }

  private String statusOf(JsonNode page, String id) {
    for (JsonNode row : page.get("content")) {
      if (row.get("id").asText().equals(id)) {
        return row.get("status").asText();
      }
    }
    return null;
  }

  private String body(String start, String end, String type, String reason) throws Exception {
    return json.writeValueAsString(
        Map.of("startDate", start, "endDate", end, "leaveType", type, "reason", reason));
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

  private Team team(String companyId, String hrId, String managerId) {
    Team t = new Team();
    t.setName("Team " + hrId);
    t.setCompanyId(companyId);
    t.setHrUserId(hrId);
    t.setManagerUserId(managerId);
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
    e.setEmployeeCode("ACME-EMP-" + (int) (Math.random() * 1000000));
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
