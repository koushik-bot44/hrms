package com.ihrms.manager;

import com.ihrms.accountant.dto.AccountantDtos.MyTeamView;
import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.manager.dto.ManagerDtos.ApprovalView;
import com.ihrms.manager.dto.ManagerDtos.NotificationFeed;
import com.ihrms.manager.dto.ManagerDtos.NotificationView;
import com.ihrms.review.EmployeeRecordAssembler;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manager inbox (§2/§3.3): the Manager's own notifications + the pending approvals routed to their
 * team. Approving an employee is the final step in v1 — it MINTS the unique employee ID (§5) and
 * regenerates the onboarding PDFs so the ID appears on them. Everything is scoped to the acting
 * Manager; decisions are audited and notify the originating HR.
 */
@Service
public class ManagerService {

  private final NotificationRepository notifications;
  private final ApprovalRequestRepository approvals;
  private final EmployeeRepository employees;
  private final UserRepository users;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final EmployeeRecordAssembler assembler;
  private final AuditService audit;

  public ManagerService(
      NotificationRepository notifications,
      ApprovalRequestRepository approvals,
      EmployeeRepository employees,
      UserRepository users,
      CompanyRepository companies,
      TeamRepository teams,
      EmployeeRecordAssembler assembler,
      AuditService audit) {
    this.notifications = notifications;
    this.approvals = approvals;
    this.employees = employees;
    this.users = users;
    this.companies = companies;
    this.teams = teams;
    this.assembler = assembler;
    this.audit = audit;
  }

  // --- Team descriptor ------------------------------------------------------

  /**
   * The Manager's own team descriptor (§8a), used by the attendance analytics tab to resolve the {@code
   * teamId} it hands to the SHARED viewer components. Mirrors the Accountant's {@code /accountant/my-team}
   * (same {@link MyTeamView}, resolved via the {@code managerUser} mapping). {@code null} if unassigned.
   */
  @Transactional(readOnly = true)
  public MyTeamView myTeam(IhrmsPrincipal.User manager) {
    Team t =
        teams.findByManagerUserId(manager.userId()).stream()
            .filter(team -> manager.companyId() == null || manager.companyId().equals(team.getCompanyId()))
            .findFirst()
            .orElse(null);
    if (t == null) {
      return null;
    }
    String companyName = companies.findById(t.getCompanyId()).map(Company::getName).orElse(null);
    return new MyTeamView(
        t.getId(),
        t.getName(),
        t.getCompanyId(),
        companyName,
        t.getHrUserId() == null ? null : userName(t.getHrUserId()),
        t.getManagerUserId() == null ? null : userName(t.getManagerUserId()));
  }

  private String userName(String userId) {
    return users.findById(userId).map(u -> u.getName()).orElse(null);
  }

  // --- Notifications --------------------------------------------------------

  public NotificationFeed notifications(IhrmsPrincipal.User manager) {
    List<NotificationView> views =
        notifications.findByRecipientUserIdOrderByCreatedAtDesc(manager.userId()).stream()
            .map(this::notificationView)
            .toList();
    return new NotificationFeed(views, notifications.countByRecipientUserIdAndReadFalse(manager.userId()));
  }

  public NotificationView markRead(IhrmsPrincipal.User manager, String notificationId) {
    Notification n =
        notifications
            .findByIdAndRecipientUserId(notificationId, manager.userId())
            .orElseThrow(() -> notFound("Notification not found"));
    n.setRead(true);
    notifications.save(n);
    return notificationView(n);
  }

  @Transactional
  public NotificationFeed markAllRead(IhrmsPrincipal.User manager) {
    List<Notification> unread = notifications.findByRecipientUserIdAndReadFalse(manager.userId());
    unread.forEach(n -> n.setRead(true));
    notifications.saveAll(unread);
    return notifications(manager);
  }

  // --- Team onboarding history (read-only) ----------------------------------

  /**
   * The employees decided onto the Manager's team, most recent first — the read-only "Team onboarding"
   * history. Approval authority now sits with HR; the rows are the HR-era approvals routed to this team
   * plus any legacy manager-era decisions (approved + rejected).
   */
  public List<ApprovalView> approvalHistory(IhrmsPrincipal.User manager) {
    return approvals
        .findByManagerUserIdAndStatusInOrderByDecidedAtDesc(
            manager.userId(), List.of(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED))
        .stream()
        .map(this::approvalView)
        .toList();
  }

  /**
   * The employee record behind one of the Manager's approvals (the four forms + uploads + generated
   * PDFs, sensitive values masked) so they can review what HR verified before deciding. Scoped to the
   * Manager's own approvals (404 otherwise); a sensitive read, so it's audited.
   */
  public EmployeeRecordView recordForApproval(IhrmsPrincipal.User manager, String approvalId) {
    ApprovalRequest approval =
        approvals
            .findByIdAndManagerUserId(approvalId, manager.userId())
            .orElseThrow(() -> notFound("Approval not found"));
    Employee employee =
        employees
            .findById(approval.getEmployeeId())
            .orElseThrow(() -> notFound("Employee not found"));
    audit.record(
        AuditActor.from(manager),
        "EMPLOYEE_RECORD_VIEWED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("email", employee.getEmail(), "approvalRequestId", approval.getId()),
        null);
    return assembler.build(employee);
  }

  // --- internals ------------------------------------------------------------

  private NotificationView notificationView(Notification n) {
    Employee employee =
        n.getEmployeeId() == null ? null : employees.findById(n.getEmployeeId()).orElse(null);
    return new NotificationView(
        n.getId(),
        n.getType(),
        n.getEmployeeId(),
        employee == null ? null : employee.getEmployeeCode(),
        employee == null ? null : employee.getFullName(),
        n.isRead(),
        n.getCreatedAt().toString());
  }

  private ApprovalView approvalView(ApprovalRequest a) {
    Employee employee = employees.findById(a.getEmployeeId()).orElse(null);
    String hrName = users.findById(a.getHrUserId()).map(u -> u.getName()).orElse(null);
    return new ApprovalView(
        a.getId(),
        a.getStatus(),
        a.getNote(),
        a.getSubmittedAt().toString(),
        a.getDecidedAt() == null ? null : a.getDecidedAt().toString(),
        employee == null ? null : employee.getEmployeeCode(),
        employee == null ? null : employee.getFullName(),
        employee == null ? null : employee.getEmail(),
        employee == null ? null : employee.getDesignation(),
        employee == null ? null : employee.getStatus(),
        hrName);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
