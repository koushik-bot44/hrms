package com.ihrms.manager;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.EmployeeCodeService;
import com.ihrms.domain.support.EmployeeCodes;
import com.ihrms.manager.dto.ManagerDtos.ApprovalView;
import com.ihrms.manager.dto.ManagerDtos.NotificationFeed;
import com.ihrms.manager.dto.ManagerDtos.NotificationView;
import com.ihrms.manager.dto.ManagerDtos.RejectApprovalRequest;
import com.ihrms.onboarding.PdfService;
import com.ihrms.review.EmployeeRecordAssembler;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import java.time.Instant;
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
  private final EmployeeCodeService codes;
  private final PdfService pdf;
  private final EmployeeRecordAssembler assembler;
  private final MailService mail;
  private final AuditService audit;

  public ManagerService(
      NotificationRepository notifications,
      ApprovalRequestRepository approvals,
      EmployeeRepository employees,
      UserRepository users,
      CompanyRepository companies,
      EmployeeCodeService codes,
      PdfService pdf,
      EmployeeRecordAssembler assembler,
      MailService mail,
      AuditService audit) {
    this.notifications = notifications;
    this.approvals = approvals;
    this.employees = employees;
    this.users = users;
    this.companies = companies;
    this.codes = codes;
    this.pdf = pdf;
    this.assembler = assembler;
    this.mail = mail;
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

  @Transactional
  public ApprovalView approve(IhrmsPrincipal.User manager, String approvalId) {
    ApprovalRequest approval = requirePending(manager, approvalId);
    approval.setStatus(ApprovalStatus.APPROVED);
    approval.setDecidedAt(Instant.now());
    approvals.save(approval);

    Employee employee =
        employees
            .findById(approval.getEmployeeId())
            .orElseThrow(() -> notFound("Employee not found"));
    // The moment the employee ID is born: mint it from the atomic per-company sequence (§5).
    if (employee.getEmployeeCode() == null) {
      employee.setEmployeeCode(allocateCode(employee));
    }
    employee.setStatus(EmployeeStatus.APPROVED);
    employees.save(employee);

    // Regenerate the PDFs so the freshly-minted employee ID is stamped onto them.
    pdf.generateForEmployee(employee);

    notifyHr(approval, NotificationType.EMPLOYEE_APPROVED);
    mail.sendEmployeeWelcome(employee.getEmail(), employee.getFullName(), employee.getEmployeeCode());

    audit.record(
        AuditActor.from(manager),
        "APPROVAL_APPROVED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of(
            "approvalRequestId", approval.getId(), "employeeCode", employee.getEmployeeCode()),
        null);
    return approvalView(approval);
  }

  /** Allocate the next unique employee code for the employee's company (atomic, collision-safe, §5). */
  private String allocateCode(Employee employee) {
    Company company =
        companies
            .findById(employee.getCompanyId())
            .orElseThrow(() -> notFound("Company not found"));
    int sequence = codes.allocateSequence(employee.getCompanyId());
    if (sequence > EmployeeCodes.SEQ_MAX) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Employee ID sequence exhausted for this company");
    }
    return EmployeeCodes.format(company.getCode(), sequence);
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
