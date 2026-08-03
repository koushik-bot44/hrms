package com.ihrms.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
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
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.AuditLogRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The Manager area after the HR-approves inversion (contract §2/§3.3): own-team notifications + a
 * READ-ONLY team-onboarding history (who joined, decided-by) + the record behind a decision. The Manager
 * no longer approves/rejects — those endpoints (and the pending inbox) are GONE (404). DB only.
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
  @Autowired Form2InfoRepository form2s;
  @Autowired NotificationRepository notifications;
  @Autowired AuditLogRepository auditLogs;
  @Autowired JdbcTemplate jdbc;

  // The record view presigns document URLs, so storage is mocked (DB-only test).
  @MockBean com.ihrms.storage.StorageService storage;

  private String companyA;
  private User hr1;
  private User manager1;
  private String manager1Token;
  private String manager2Token;
  private Team team;
  private Employee employee; // APPROVED onto manager1's team (a joined team member)
  private ApprovalRequest approval; // the HR-era APPROVED decision record
  private Notification notification; // the "approved onto your team" bell

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\","
            + "\"form1_personal\",\"form2_info\",\"form3_prev_employment\",\"documents\",\"signatures\",\"generated_documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
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
    employee.setStatus(EmployeeStatus.APPROVED);
    employee.setEmployeeCode("AAA-EMP-000001"); // minted when HR approved
    employees.save(employee);

    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(employee.getId());
    f2.setData(Map.of("fullName", "Evan Stone", "personalEmail", "evan@personal.test"));
    form2s.save(f2);

    // HR-era decision record: created + decided, routed onto manager1's team.
    approval = decidedApproval(employee, manager1.getId(), ApprovalStatus.APPROVED, "Looks good");

    // The Manager's durable bell that the employee joined their team.
    notification = new Notification();
    notification.setRecipientUserId(manager1.getId());
    notification.setType(NotificationType.EMPLOYEE_APPROVED);
    notification.setEmployeeId(employee.getId());
    notifications.save(notification);

    when(storage.presignedGetUrl(any(), anyInt())).thenReturn("http://storage.local/get?sig=test");
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
    assertThat(body.get("notifications").get(0).get("type").asText()).isEqualTo("EMPLOYEE_APPROVED");
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
  void teamOnboardingHistoryListsDecidedRowsAcrossBothEras() throws Exception {
    // Add a legacy MANAGER-era rejected decision on the same team, to prove both eras surface read-only.
    Employee rita = new Employee();
    rita.setFullName("Rita Reject");
    rita.setEmail("rita@personal.test");
    rita.setCompanyId(companyA);
    rita.setOnboardingHrId(hr1.getId());
    rita.setStatus(EmployeeStatus.REJECTED);
    employees.save(rita);
    decidedApproval(rita, manager1.getId(), ApprovalStatus.REJECTED, "Manager-era reject");

    MvcResult history =
        mvc.perform(get("/manager/approvals/history").header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode items = json.readTree(history.getResponse().getContentAsString());
    assertThat(items).hasSize(2); // the HR-era APPROVED + the legacy manager-era REJECTED
    assertThat(items).anySatisfy(i -> {
      assertThat(i.get("status").asText()).isEqualTo("APPROVED");
      assertThat(i.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
      assertThat(i.get("decidedAt").isNull()).isFalse();
    });
    assertThat(items).anySatisfy(i -> assertThat(i.get("status").asText()).isEqualTo("REJECTED"));

    // Another manager's history stays empty (scoped to managerUserId).
    MvcResult otherHistory =
        mvc.perform(get("/manager/approvals/history").header("Authorization", "Bearer " + manager2Token))
            .andExpect(status().isOk())
            .andReturn();
    assertThat(json.readTree(otherHistory.getResponse().getContentAsString())).isEmpty();
  }

  @Test
  void managerCanViewTheRecordBehindADecisionButNotOthers() throws Exception {
    MvcResult rec =
        mvc.perform(get("/manager/approvals/" + approval.getId() + "/record")
                .header("Authorization", "Bearer " + manager1Token))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode body = json.readTree(rec.getResponse().getContentAsString());
    assertThat(body.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(body.get("fullName").asText()).isEqualTo("Evan Stone");
    assertThat(body.get("email").asText()).isEqualTo("evan@personal.test");
    assertThat(body.get("form1").isNull()).isTrue(); // no forms filled in this fixture
    assertThat(body.get("documents")).isEmpty();
    assertThat(auditLogs.findByAction("EMPLOYEE_RECORD_VIEWED")).isNotEmpty(); // a sensitive read -> audited

    // A different manager cannot view it (scoped to managerUserId).
    mvc.perform(get("/manager/approvals/" + approval.getId() + "/record")
            .header("Authorization", "Bearer " + manager2Token))
        .andExpect(status().isNotFound());
  }

  @Test
  void theManagerApproveRejectAndPendingInboxEndpointsAreGone() throws Exception {
    // Approval authority moved to HR — the Manager's pending inbox + decision endpoints no longer exist.
    mvc.perform(get("/manager/approvals").header("Authorization", "Bearer " + manager1Token))
        .andExpect(status().isNotFound());
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/approve")
            .header("Authorization", "Bearer " + manager1Token))
        .andExpect(status().isNotFound());
    mvc.perform(post("/manager/approvals/" + approval.getId() + "/reject")
            .header("Authorization", "Bearer " + manager1Token))
        .andExpect(status().isNotFound());
  }

  // --- fixtures -------------------------------------------------------------

  private ApprovalRequest decidedApproval(
      Employee e, String managerUserId, ApprovalStatus status, String note) {
    ApprovalRequest a = new ApprovalRequest();
    a.setEmployeeId(e.getId());
    a.setHrUserId(hr1.getId());
    a.setManagerUserId(managerUserId);
    a.setTeamId(team.getId());
    a.setStatus(status);
    a.setNote(note);
    a.setDecidedAt(Instant.now());
    return approvals.save(a);
  }

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
