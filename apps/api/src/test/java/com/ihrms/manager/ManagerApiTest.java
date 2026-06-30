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

  private String companyA;
  private User hr1;
  private User manager1;
  private String manager1Token;
  private String manager2Token;
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
    Team team = team(companyA, hr1.getId(), manager1.getId());
    manager1Token = managerToken(manager1);
    manager2Token = managerToken(manager2);

    employee = new Employee();
    employee.setEmployeeCode("AAA-EMP-000001");
    employee.setEmail("evan@personal.test");
    employee.setCompanyId(companyA);
    employee.setOnboardingHrId(hr1.getId());
    employee.setStatus(EmployeeStatus.HR_VERIFIED);
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
    assertThat(body.get("notifications").get(0).get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
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
    assertThat(item.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(item.get("employeeStatus").asText()).isEqualTo("HR_VERIFIED");
    assertThat(item.get("hrName").asText()).isEqualTo("hr1@a.test");

    MvcResult decided =
        mvc.perform(post("/manager/approvals/" + approval.getId() + "/approve")
                .header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(decided.getResponse().getContentAsString()).get("status").asText())
        .isEqualTo("APPROVED");

    assertThat(employees.findById(employee.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.APPROVED);
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

    assertThat(employees.findById(employee.getId()).orElseThrow().getStatus())
        .isEqualTo(EmployeeStatus.REJECTED);
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
