package com.ihrms.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.EmployeeCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
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

/**
 * Manager inbox (contract §2/§3.3): own-team notifications + approvals, mark-read, approve/reject
 * with notifications back to HR, cross-team/cross-company denial, and audit. DB only (no storage).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ManagerApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;
  @Autowired ManagerService managerService;

  private String companyA;
  private User hr1;
  private User manager1;
  private String manager1Token;
  private String manager2Token;
  private Team team;
  private Employee employee;
  private ApprovalRequest approval;
  private Notification notification;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    hr1 = user(companyA, UserRole.HR, "hr1@a.test");
    manager1 = user(companyA, UserRole.MANAGER, "mgr1@a.test");
    User manager2 = user(companyA, UserRole.MANAGER, "mgr2@a.test");
    team = team(companyA, hr1.getId(), manager1.getId());
    manager1Token = managerToken(manager1);
    manager2Token = managerToken(manager2);

    employee = new Employee();
    employee.setFullName("Evan Stone");
    employee.setEmail("evan@personal.test");
    employee.setDesignation("Software Engineer");
    employee.setCompanyId(companyA);
    employee.setOnboardingHrId(hr1.getId());
    employee.setStatus(EmployeeStatus.HR_VERIFIED);
    // no employeeCode — minted on approval (§5)
    employees.save(employee);

    approval = new ApprovalRequest();
    approval.setEmployeeId(employee.getId());
    approval.setHrUserId(hr1.getId());
    approval.setManagerUserId(manager1.getId());
    approval.setTeamId(team.getId());
    approval.setStatus(ApprovalStatus.PENDING);
    approvals.save(approval);

    notification = new Notification();
    notification.setRecipientUserId(manager1.getId());
    notification.setType(NotificationType.APPROVAL_REQUESTED);
    notification.setEmployeeId(employee.getId());
    notifications.save(notification);
  }

  @Test
  void notificationFeedListsOwnNotificationsAndMarkReadWorks() throws Exception {
    MvcResult feed =
        mvc.perform(get("/manager/notifications").header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(feed.getResponse().getContentAsString());
    assertThat(body.get("unreadCount").asInt()).isEqualTo(1);
    assertThat(body.get("notifications")).hasSize(1);
    assertThat(body.get("notifications").get(0).get("type").asText()).isEqualTo("APPROVAL_REQUESTED");
    assertThat(body.get("notifications").get(0).get("fullName").asText()).isEqualTo("Evan Stone");
    assertThat(body.get("notifications").get(0).get("read").asBoolean()).isFalse();

    mvc.perform(post("/manager/notifications/" + notification.getId() + "/read")
            .header("Authorization", "Bearer " + manager1Token))
        .andExpect(status().isOk());
    assertThat(notifications.countByRecipientUserIdAndReadFalse(manager1.getId())).isZero();

    // Another manager sees none of these and cannot mark them read.
    MvcResult otherFeed =
        mvc.perform(get("/manager/notifications").header("Authorization", "Bearer " + manager2Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(otherFeed.getResponse().getContentAsString()).get("notifications")).isEmpty();
    mvc.perform(post("/manager/notifications/" + notification.getId() + "/read")
            .header("Authorization", "Bearer " + manager2Token))
        .andExpect(status().isNotFound());
  }

  @Test
  void approveSetsEmployeeApprovedAndNotifiesHr() throws Exception {
    MvcResult queue =
        mvc.perform(get("/manager/approvals").header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode item = json.readTree(queue.getResponse().getContentAsString()).get(0);
    assertThat(item.get("employeeCode").isNull()).isTrue(); // no ID until approval (§5)
    assertThat(item.get("fullName").asText()).isEqualTo("Evan Stone");
    assertThat(item.get("designation").asText()).isEqualTo("Software Engineer");
    assertThat(item.get("employeeStatus").asText()).isEqualTo("HR_VERIFIED");
    assertThat(item.get("hrName").asText()).isEqualTo("hr1@a.test");

    MvcResult decided =
        mvc.perform(post("/manager/approvals/" + approval.getId() + "/approve")
                .header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode result = json.readTree(decided.getResponse().getContentAsString());
    assertThat(result.get("status").asText()).isEqualTo("APPROVED");
    // The unique ID is MINTED on approval and surfaced in the response (§5).
    assertThat(result.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");

    Employee approved = employees.findById(employee.getId()).orElseThrow();
    assertThat(approved.getStatus()).isEqualTo(EmployeeStatus.APPROVED);
    assertThat(approved.getEmployeeCode()).isEqualTo("AAA-EMP-000001");
    assertThat(EmployeeCodes.EMPLOYEE_CODE.matcher(approved.getEmployeeCode()).matches()).isTrue();
    assertThat(notifications.findByRecipientUserId(hr1.getId()))
        .anySatisfy(n -> assertThat(n.getType()).isEqualTo(NotificationType.EMPLOYEE_APPROVED));
    assertThat(auditLogs.findByAction("APPROVAL_APPROVED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getCompanyId()).isEqualTo(companyA));

    // No longer pending; a second decision is a conflict.
    MvcResult emptyQueue =
        mvc.perform(get("/manager/approvals").header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(emptyQueue.getResponse().getContentAsString())).isEmpty();
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/approve")
            .header("Authorization", "Bearer " + manager1Token))
        .andExpect(status().isConflict());
  }

  @Test
  void rejectRequiresANoteAndNotifiesHr() throws Exception {
    // A note is required.
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/reject")
            .header("Authorization", "Bearer " + manager1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());

    mvc.perform(post("/manager/approvals/" + approval.getId() + "/reject")
            .header("Authorization", "Bearer " + manager1Token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "AADHAAR is missing"))))
        .andExpect(status().isOk());

    Employee rejected = employees.findById(employee.getId()).orElseThrow();
    assertThat(rejected.getStatus()).isEqualTo(EmployeeStatus.REJECTED);
    assertThat(rejected.getEmployeeCode()).isNull(); // reject allocates NO code (§5)
    assertThat(notifications.findByRecipientUserId(hr1.getId()))
        .anySatisfy(n -> assertThat(n.getType()).isEqualTo(NotificationType.EMPLOYEE_REJECTED));
    assertThat(auditLogs.findByAction("APPROVAL_REJECTED"))
        .singleElement()
        .satisfies(r -> assertThat(r.getMetadata()).containsEntry("note", "AADHAAR is missing"));
  }

  @Test
  void managerSeesOnlyTheirOwnTeamApprovals() throws Exception {
    // A different manager (same company) sees an empty queue and cannot decide.
    MvcResult queue =
        mvc.perform(get("/manager/approvals").header("Authorization", "Bearer " + manager2Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(queue.getResponse().getContentAsString())).isEmpty();
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/approve")
            .header("Authorization", "Bearer " + manager2Token))
        .andExpect(status().isNotFound());

    // A manager in another company is likewise denied.
    String companyB = company("BBB");
    String managerB = managerToken(user(companyB, UserRole.MANAGER, "mgrB@b.test"));
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/reject")
            .header("Authorization", "Bearer " + managerB)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("note", "nope"))))
        .andExpect(status().isNotFound());
  }

  @Test
  void approvalHistoryListsDecidedDecisionsScopedToTheManager() throws Exception {
    // Nothing decided yet -> empty history.
    MvcResult before =
        mvc.perform(
                get("/manager/approvals/history").header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(before.getResponse().getContentAsString())).isEmpty();

    // Approve -> it leaves the pending queue and shows up in history.
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/approve")
            .header("Authorization", "Bearer " + manager1Token))
        .andExpect(status().isOk());

    MvcResult history =
        mvc.perform(
                get("/manager/approvals/history").header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode items = json.readTree(history.getResponse().getContentAsString());
    assertThat(items).hasSize(1);
    assertThat(items.get(0).get("status").asText()).isEqualTo("APPROVED");
    assertThat(items.get(0).get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(items.get(0).get("decidedAt").isNull()).isFalse();

    // Another manager's history stays empty (scoped to managerUserId).
    MvcResult otherHistory =
        mvc.perform(
                get("/manager/approvals/history").header("Authorization", "Bearer " + manager2Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(otherHistory.getResponse().getContentAsString())).isEmpty();
  }

  @Test
  void managerCanViewTheRecordBehindTheirApprovalButNotOthers() throws Exception {
    MvcResult rec =
        mvc.perform(
                get("/manager/approvals/" + approval.getId() + "/record")
                    .header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(rec.getResponse().getContentAsString());
    assertThat(body.get("employeeCode").isNull()).isTrue(); // viewed pre-approval -> no ID yet
    assertThat(body.get("fullName").asText()).isEqualTo("Evan Stone");
    assertThat(body.get("email").asText()).isEqualTo("evan@personal.test");
    assertThat(body.get("sections")).isEmpty();
    assertThat(body.get("documents")).isEmpty();
    // A sensitive read -> audited.
    assertThat(auditLogs.findByAction("EMPLOYEE_RECORD_VIEWED")).isNotEmpty();

    // A different manager cannot view it (scoped to managerUserId).
    mvc.perform(
            get("/manager/approvals/" + approval.getId() + "/record")
                .header("Authorization", "Bearer " + manager2Token))
        .andExpect(status().isNotFound());
  }

  @Test
  void parallelApprovalsAllocateDistinctWellFormedCodes() {
    IhrmsPrincipal.User mgr =
        new IhrmsPrincipal.User(
            manager1.getId(), manager1.getEmail(), manager1.getName(), UserRole.MANAGER, companyA, null);
    int n = 12;
    List<String> approvalIds = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      Employee e = new Employee();
      e.setFullName("Emp " + i);
      e.setEmail("emp" + i + "@p.test");
      e.setCompanyId(companyA);
      e.setOnboardingHrId(hr1.getId());
      e.setStatus(EmployeeStatus.HR_VERIFIED);
      employees.save(e);
      ApprovalRequest a = new ApprovalRequest();
      a.setEmployeeId(e.getId());
      a.setHrUserId(hr1.getId());
      a.setManagerUserId(manager1.getId());
      a.setTeamId(team.getId());
      a.setStatus(ApprovalStatus.PENDING);
      approvals.save(a);
      approvalIds.add(a.getId());
    }

    // Approve all in parallel within the same company -> the atomic sequence must not collide.
    Set<String> codes =
        approvalIds.parallelStream()
            .map(id -> managerService.approve(mgr, id).employeeCode())
            .collect(Collectors.toCollection(ConcurrentHashMap::newKeySet));

    assertThat(codes).hasSize(n); // no duplicate codes under concurrency
    assertThat(codes).allMatch(c -> EmployeeCodes.EMPLOYEE_CODE.matcher(c).matches());
  }

  // --- fixtures -------------------------------------------------------------

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private Team team(String companyId, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Engineering");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    return teams.save(t);
  }

  private String managerToken(User u) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(
            u.getId(), u.getEmail(), u.getName(), UserRole.MANAGER, u.getCompanyId(), null));
  }
}
