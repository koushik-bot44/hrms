package com.ihrms.manager;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.manager.dto.ManagerDtos.ApprovalView;
import com.ihrms.manager.dto.ManagerDtos.NotificationFeed;
import com.ihrms.manager.dto.ManagerDtos.NotificationView;
import com.ihrms.manager.dto.ManagerDtos.RejectApprovalRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manager inbox (ARCHITECTURE.md §2/§3.3): the Manager's own notifications + the pending approvals
 * routed to their team. Approving an employee is the final step in v1. Everything is scoped to the
 * acting Manager (recipientUserId / managerUserId == self — the §6 tenancy filter); decisions are
 * audited and notify the originating HR.
 */
@Service
public class ManagerService {

  private final NotificationRepository notifications;
  private final ApprovalRequestRepository approvals;
  private final EmployeeRepository employees;
  private final UserRepository users;
  private final AuditService audit;

  public ManagerService(
      NotificationRepository notifications,
      ApprovalRequestRepository approvals,
      EmployeeRepository employees,
      UserRepository users,
      AuditService audit) {
    this.notifications = notifications;
    this.approvals = approvals;
    this.employees = employees;
    this.users = users;
    this.audit = audit;
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

  // --- Approvals ------------------------------------------------------------

  public List<ApprovalView> pendingApprovals(IhrmsPrincipal.User manager) {
    return approvals
        .findByManagerUserIdAndStatusOrderBySubmittedAtAsc(manager.userId(), ApprovalStatus.PENDING)
        .stream()
        .map(this::approvalView)
        .toList();
  }

  /** Past decisions (approved + rejected) the Manager has made, most recent first. */
  public List<ApprovalView> approvalHistory(IhrmsPrincipal.User manager) {
    return approvals
        .findByManagerUserIdAndStatusInOrderByDecidedAtDesc(
            manager.userId(), List.of(ApprovalStatus.APPROVED, ApprovalStatus.REJECTED))
        .stream()
        .map(this::approvalView)
        .toList();
  }

  @Transactional
  public ApprovalView approve(IhrmsPrincipal.User manager, String approvalId) {
    ApprovalRequest approval = requirePending(manager, approvalId);
    approval.setStatus(ApprovalStatus.APPROVED);
    approval.setDecidedAt(Instant.now());
    approvals.save(approval);

    Employee employee = setEmployeeStatus(approval, EmployeeStatus.APPROVED);
    notifyHr(approval, NotificationType.EMPLOYEE_APPROVED);

    audit.record(
        AuditActor.from(manager),
        "APPROVAL_APPROVED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("approvalRequestId", approval.getId()),
        null);
    return approvalView(approval);
  }

  @Transactional
  public ApprovalView reject(IhrmsPrincipal.User manager, String approvalId, RejectApprovalRequest req) {
    ApprovalRequest approval = requirePending(manager, approvalId);
    approval.setStatus(ApprovalStatus.REJECTED);
    approval.setNote(req.note());
    approval.setDecidedAt(Instant.now());
    approvals.save(approval);

    Employee employee = setEmployeeStatus(approval, EmployeeStatus.REJECTED);
    notifyHr(approval, NotificationType.EMPLOYEE_REJECTED);

    audit.record(
        AuditActor.from(manager),
        "APPROVAL_REJECTED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("approvalRequestId", approval.getId(), "note", req.note()),
        null);
    return approvalView(approval);
  }

  // --- internals ------------------------------------------------------------

  private ApprovalRequest requirePending(IhrmsPrincipal.User manager, String approvalId) {
    ApprovalRequest approval =
        approvals
            .findByIdAndManagerUserId(approvalId, manager.userId())
            .orElseThrow(() -> notFound("Approval not found"));
    if (approval.getStatus() != ApprovalStatus.PENDING) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This approval was already " + approval.getStatus());
    }
    return approval;
  }

  private Employee setEmployeeStatus(ApprovalRequest approval, EmployeeStatus status) {
    Employee employee =
        employees.findById(approval.getEmployeeId()).orElseThrow(() -> notFound("Employee not found"));
    employee.setStatus(status);
    employees.save(employee);
    return employee;
  }

  private void notifyHr(ApprovalRequest approval, NotificationType type) {
    Notification n = new Notification();
    n.setRecipientUserId(approval.getHrUserId());
    n.setType(type);
    n.setEmployeeId(approval.getEmployeeId());
    notifications.save(n);
  }

  private NotificationView notificationView(Notification n) {
    String employeeCode =
        n.getEmployeeId() == null
            ? null
            : employees.findById(n.getEmployeeId()).map(Employee::getEmployeeCode).orElse(null);
    return new NotificationView(
        n.getId(), n.getType(), n.getEmployeeId(), employeeCode, n.isRead(), n.getCreatedAt().toString());
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
        employee == null ? null : employee.getEmail(),
        employee == null ? null : employee.getStatus(),
        hrName);
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
