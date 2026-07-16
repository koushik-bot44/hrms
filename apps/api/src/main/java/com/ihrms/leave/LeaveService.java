package com.ihrms.leave;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.LeaveRequest;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.LeaveRequestRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.leave.dto.LeaveDtos.LeaveMinePage;
import com.ihrms.leave.dto.LeaveDtos.LeaveRequestView;
import com.ihrms.leave.dto.LeaveDtos.SubmitLeaveRequest;
import com.ihrms.leave.dto.LeaveDtos.TeamLeavePage;
import com.ihrms.leave.dto.LeaveDtos.TeamLeaveRow;
import com.ihrms.mail.InternalMailService;
import com.ihrms.mail.MailPushNotifier;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageResult;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Leave requests (§8b). A credentialed employee submits; the request routes to the Manager on the
 * employee's onboarding-HR's team (the resolved approver, stored on the row — the SAME resolution as
 * approvals). The Manager decides; the employee is emailed + sees it in their history. No leave balances.
 */
@Service
public class LeaveService {

  private static final Logger log = LoggerFactory.getLogger(LeaveService.class);
  private static final DateTimeFormatter LEAVE_DATE = DateTimeFormatter.ofPattern("d MMM yyyy");

  private final LeaveRequestRepository leaves;
  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final NotificationRepository notifications;
  private final MailService mail;
  private final InternalMailService internalMail;
  private final MailPushNotifier pushNotifier;
  private final AuditService audit;

  public LeaveService(
      LeaveRequestRepository leaves,
      EmployeeRepository employees,
      TeamRepository teams,
      NotificationRepository notifications,
      MailService mail,
      InternalMailService internalMail,
      MailPushNotifier pushNotifier,
      AuditService audit) {
    this.leaves = leaves;
    this.employees = employees;
    this.teams = teams;
    this.notifications = notifications;
    this.mail = mail;
    this.internalMail = internalMail;
    this.pushNotifier = pushNotifier;
    this.audit = audit;
  }

  // --- Employee ------------------------------------------------------------

  @Transactional
  public LeaveRequestView submit(IhrmsPrincipal actor, SubmitLeaveRequest req, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    if (req.endDate().isBefore(req.startDate())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "End date must be on or after the start date");
    }
    String managerUserId = resolveApprover(me);

    LeaveRequest leave = new LeaveRequest();
    leave.setEmployeeId(me.getId());
    leave.setCompanyId(me.getCompanyId());
    leave.setManagerUserId(managerUserId);
    leave.setStartDate(req.startDate());
    leave.setEndDate(req.endDate());
    leave.setLeaveType(req.leaveType());
    leave.setReason(req.reason());
    leave.setStatus(LeaveStatus.PENDING);
    leaves.save(leave);

    // Real bell entry for the Manager (§8b) — unlike attendance, leave needs a decision.
    Notification n = new Notification();
    n.setRecipientUserId(managerUserId);
    n.setType(NotificationType.LEAVE_REQUESTED);
    n.setEmployeeId(me.getId());
    notifications.save(n);

    audit.record(
        AuditActor.from(actor),
        "LEAVE_REQUESTED",
        "LeaveRequest",
        leave.getId(),
        Map.<String, Object>of(
            "employeeId", me.getId(),
            "managerUserId", managerUserId,
            "leaveType", leave.getLeaveType().name()),
        ip);
    // A courtesy internal mail to the Manager is sent by the controller AFTER this tx commits
    // (best-effort) — see mailManagerAfterSubmit.
    return view(leave);
  }

  @Transactional(readOnly = true)
  public LeaveMinePage myHistory(IhrmsPrincipal actor, Pageable pageable) {
    Employee me = requireCredentialedEmployee(actor);
    Page<LeaveRequest> page = leaves.findByEmployeeIdOrderByCreatedAtDesc(me.getId(), pageable);
    List<LeaveRequestView> content = page.getContent().stream().map(this::view).toList();
    return new LeaveMinePage(
        content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  @Transactional
  public LeaveRequestView cancel(IhrmsPrincipal actor, String id, String ip) {
    Employee me = requireCredentialedEmployee(actor);
    LeaveRequest leave = leaves.findById(id).orElseThrow(LeaveService::notFound);
    if (!leave.getEmployeeId().equals(me.getId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not your leave request");
    }
    if (!leave.isPending()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This request was already " + leave.getStatus().name().toLowerCase());
    }
    leave.setStatus(LeaveStatus.CANCELLED);
    leaves.save(leave);
    audit.record(AuditActor.from(actor), "LEAVE_CANCELLED", "LeaveRequest", leave.getId(), Map.of(), ip);
    return view(leave);
  }

  // --- Manager (team-scope) ------------------------------------------------

  @Transactional(readOnly = true)
  public TeamLeavePage teamQueue(
      IhrmsPrincipal.User manager, String status, String from, String to, Pageable pageable) {
    LeaveStatus statusFilter = parseStatus(status);
    LocalDate fromDate = parseDate(from, "from");
    LocalDate toDate = parseDate(to, "to");
    Specification<LeaveRequest> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("managerUserId"), manager.userId())); // routed to this manager
          p.add(cb.equal(root.get("companyId"), manager.companyId())); // tenant filter
          if (statusFilter != null) p.add(cb.equal(root.get("status"), statusFilter));
          if (fromDate != null) p.add(cb.greaterThanOrEqualTo(root.get("startDate"), fromDate));
          if (toDate != null) p.add(cb.lessThanOrEqualTo(root.get("startDate"), toDate));
          return cb.and(p.toArray(new Predicate[0]));
        };
    Page<LeaveRequest> page = leaves.findAll(spec, pageable);
    Map<String, Employee> byId = employeeMap(page.getContent());
    List<TeamLeaveRow> rows =
        page.getContent().stream().map(l -> teamRow(l, byId.get(l.getEmployeeId()))).toList();
    return new TeamLeavePage(
        rows, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
  }

  @Transactional
  public TeamLeaveRow decide(
      IhrmsPrincipal.User manager, String id, boolean approve, String note, String ip) {
    LeaveRequest leave = leaves.findById(id).orElseThrow(LeaveService::notFound);
    // Only the RESOLVED approver for this request (same manager, same tenant) may decide.
    if (!leave.getManagerUserId().equals(manager.userId())
        || !leave.getCompanyId().equals(manager.companyId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You cannot decide this leave request");
    }
    if (!leave.isPending()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This request was already " + leave.getStatus().name().toLowerCase());
    }
    String cleanNote = note == null || note.isBlank() ? null : note.trim();
    if (!approve && cleanNote == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "A note is required to reject a leave request");
    }
    leave.setStatus(approve ? LeaveStatus.APPROVED : LeaveStatus.REJECTED);
    leave.setDecisionNote(cleanNote);
    leave.setDecidedAt(Instant.now());
    leave.setDecidedByUserId(manager.userId());
    leaves.save(leave);

    // The employee has no notification inbox — email them + they see it in their leave history (§8b).
    Employee employee = employees.findById(leave.getEmployeeId()).orElse(null);
    if (employee != null) {
      mail.sendLeaveDecision(employee.getEmail(), approve, cleanNote);
    }
    audit.record(
        AuditActor.from(manager),
        approve ? "LEAVE_APPROVED" : "LEAVE_REJECTED",
        "LeaveRequest",
        leave.getId(),
        cleanNote == null
            ? Map.<String, Object>of("employeeId", leave.getEmployeeId())
            : Map.<String, Object>of("employeeId", leave.getEmployeeId(), "note", cleanNote),
        ip);
    // A courtesy internal mail to the employee is sent by the controller AFTER this tx commits
    // (best-effort) — see mailEmployeeAfterDecision.
    return teamRow(leave, employee);
  }

  // --- helpers -------------------------------------------------------------

  // --- Courtesy internal mail (§8/§8b) --------------------------------------
  //
  // On top of the in-app bell / leave-history / dev-logged email, we drop a real, repliable internal
  // mail into both parties' mailboxes via the ordinary canSendMail-guarded InternalMailService path.
  // These run from the CONTROLLER, AFTER the leave transaction has committed, so InternalMailService's
  // own @Transactional opens a fresh transaction that actually commits (a send registered inside the
  // leave tx would silently join the already-committed tx and never flush). Best-effort: swallowed on
  // failure so a mail hiccup (not permitted, recipient not credentialed, transport error) can never
  // roll back — or fail — the leave action itself. Exactly one mail per submission / per decision.

  /** Employee → resolved Manager, summarizing a just-submitted request. Best-effort. */
  public void mailManagerAfterSubmit(IhrmsPrincipal actor, String leaveId, String ip) {
    try {
      LeaveRequest leave = leaves.findById(leaveId).orElse(null);
      if (leave == null || leave.getManagerUserId() == null) {
        return;
      }
      Employee me = employees.findById(leave.getEmployeeId()).orElse(null);
      String employeeName = me != null ? me.getFullName() : "An employee";
      String subject = "Leave request: " + employeeName + " (" + leave.getLeaveType().name() + ")";
      StringBuilder body = new StringBuilder();
      body
          .append(employeeName)
          .append(" has requested ")
          .append(leave.getLeaveType().name())
          .append(" leave.\n\n");
      body.append("Dates: ").append(dateRange(leave)).append("\n");
      if (leave.getReason() != null && !leave.getReason().isBlank()) {
        body.append("Reason: ").append(leave.getReason().trim()).append("\n");
      }
      body.append("\nPlease review this request in the Leave workspace.");
      SendMessageResult sent =
          internalMail.send(actor, mailTo(leave.getManagerUserId(), subject, body.toString()), ip);
      // The courtesy mail is real internal mail — the recipient also gets the OS push (§ Web Push N3).
      pushNotifier.notifyRecipients(sent.id());
    } catch (RuntimeException e) {
      log.warn("Leave submit courtesy mail skipped (best-effort): {}", e.getMessage());
    }
  }

  /** Deciding Manager → employee, stating the outcome + any note. Best-effort. */
  public void mailEmployeeAfterDecision(IhrmsPrincipal.User manager, String leaveId, String ip) {
    try {
      LeaveRequest leave = leaves.findById(leaveId).orElse(null);
      if (leave == null) {
        return;
      }
      String outcome = leave.getStatus() == LeaveStatus.APPROVED ? "approved" : "rejected";
      String subject = "Leave request " + outcome;
      StringBuilder body = new StringBuilder();
      body
          .append("Your ")
          .append(leave.getLeaveType().name())
          .append(" leave request for ")
          .append(dateRange(leave))
          .append(" has been ")
          .append(outcome)
          .append(".\n");
      if (leave.getDecisionNote() != null) {
        body.append("\nNote from your manager: ").append(leave.getDecisionNote()).append("\n");
      }
      SendMessageResult sent =
          internalMail.send(manager, mailTo(leave.getEmployeeId(), subject, body.toString()), ip);
      pushNotifier.notifyRecipients(sent.id()); // OS push to the employee too (§ Web Push N3)
    } catch (RuntimeException e) {
      log.warn("Leave decision courtesy mail skipped (best-effort): {}", e.getMessage());
    }
  }

  private String dateRange(LeaveRequest leave) {
    return leave.getStartDate().format(LEAVE_DATE) + " – " + leave.getEndDate().format(LEAVE_DATE);
  }

  private static SendMessageRequest mailTo(String toId, String subject, String body) {
    return new SendMessageRequest(List.of(toId), List.of(), List.of(), subject, body, List.of());
  }

  /** Approver = employee.onboardingHr → that HR's team → team.manager (reuses the approval resolution). */
  private String resolveApprover(Employee me) {
    String noManager = "No manager is assigned to your team yet";
    if (me.getOnboardingHrId() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, noManager);
    }
    Team team =
        teams
            .findByCompanyIdAndHrUserId(me.getCompanyId(), me.getOnboardingHrId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, noManager));
    String managerUserId = team.getManagerUserId();
    if (managerUserId == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, noManager);
    }
    return managerUserId;
  }

  private Employee requireCredentialedEmployee(IhrmsPrincipal actor) {
    if (!(actor instanceof IhrmsPrincipal.Employee principal)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Leave is for employees");
    }
    Employee employee =
        employees
            .findById(principal.employeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown account"));
    if (employee.getMailAddress() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You do not have leave access");
    }
    return employee;
  }

  private Map<String, Employee> employeeMap(List<LeaveRequest> rows) {
    Set<String> ids = rows.stream().map(LeaveRequest::getEmployeeId).collect(Collectors.toSet());
    if (ids.isEmpty()) {
      return Map.of();
    }
    return employees.findAllById(ids).stream().collect(Collectors.toMap(Employee::getId, e -> e));
  }

  private LeaveRequestView view(LeaveRequest l) {
    return new LeaveRequestView(
        l.getId(),
        l.getStartDate().toString(),
        l.getEndDate().toString(),
        l.getLeaveType(),
        l.getReason(),
        l.getStatus(),
        l.getDecisionNote(),
        l.getDecidedAt() == null ? null : l.getDecidedAt().toString(),
        l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
  }

  private TeamLeaveRow teamRow(LeaveRequest l, Employee e) {
    return new TeamLeaveRow(
        l.getId(),
        l.getEmployeeId(),
        e == null ? null : e.getEmployeeCode(),
        e == null ? null : e.getFullName(),
        l.getStartDate().toString(),
        l.getEndDate().toString(),
        l.getLeaveType(),
        l.getReason(),
        l.getStatus(),
        l.getDecisionNote(),
        l.getDecidedAt() == null ? null : l.getDecidedAt().toString(),
        l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
  }

  private LeaveStatus parseStatus(String status) {
    if (status == null || status.isBlank()) {
      return null;
    }
    try {
      return LeaveStatus.valueOf(status);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: " + status);
    }
  }

  private LocalDate parseDate(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return LocalDate.parse(value);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field + " date");
    }
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "Leave request not found");
  }
}
