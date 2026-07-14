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
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

  private final LeaveRequestRepository leaves;
  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final NotificationRepository notifications;
  private final MailService mail;
  private final AuditService audit;

  public LeaveService(
      LeaveRequestRepository leaves,
      EmployeeRepository employees,
      TeamRepository teams,
      NotificationRepository notifications,
      MailService mail,
      AuditService audit) {
    this.leaves = leaves;
    this.employees = employees;
    this.teams = teams;
    this.notifications = notifications;
    this.mail = mail;
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
    return teamRow(leave, employee);
  }

  // --- helpers -------------------------------------------------------------

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
