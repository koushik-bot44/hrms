package com.ihrms.offboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.offboarding.dto.OffboardingDtos.CancelRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.DecisionRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.HierarchyPendingRow;
import com.ihrms.offboarding.dto.OffboardingDtos.InitiateRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingCaseView;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingDecisionResult;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Offboarding lifecycle, stage 1 (§Offboarding): HR initiates a case for an APPROVED employee; the platform
 * HIERARCHY role approves/rejects (its ONLY write surface, with a MINIMAL-PII pending view); HR may cancel
 * pre-completion. At most one non-terminal case per employee. Employee.status is not touched in stage 1.
 * Notifications fire after commit (best-effort) via the controller, mirroring the approve/agreements pattern.
 */
@Service
public class OffboardingService {

  private static final Logger log = LoggerFactory.getLogger(OffboardingService.class);
  private static final List<OffboardingStatus> ACTIVE =
      List.of(OffboardingStatus.PENDING_APPROVAL, OffboardingStatus.APPROVED);

  private final EmployeeRepository employees;
  private final OffboardingCaseRepository cases;
  private final com.ihrms.domain.repository.OffboardingDocumentRepository docs;
  private final UserRepository users;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final NotificationRepository notifications;
  private final AuthorizationService authz;
  private final AuditService audit;
  private final MailService mail;
  private final PushService push;

  public OffboardingService(
      EmployeeRepository employees,
      OffboardingCaseRepository cases,
      com.ihrms.domain.repository.OffboardingDocumentRepository docs,
      UserRepository users,
      CompanyRepository companies,
      TeamRepository teams,
      NotificationRepository notifications,
      AuthorizationService authz,
      AuditService audit,
      MailService mail,
      PushService push) {
    this.employees = employees;
    this.cases = cases;
    this.docs = docs;
    this.users = users;
    this.companies = companies;
    this.teams = teams;
    this.notifications = notifications;
    this.authz = authz;
    this.audit = audit;
    this.mail = mail;
    this.push = push;
  }

  // --- HR: initiate / cancel / read ----------------------------------------

  /** HR initiates a case for an APPROVED employee they onboarded. 409 if not APPROVED or a case is active. */
  @Transactional
  public OffboardingCaseView initiate(
      IhrmsPrincipal.User actor, String employeeId, InitiateRequest req, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    if (employee.getStatus() != EmployeeStatus.APPROVED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Offboarding can only be initiated for an APPROVED employee");
    }
    if (cases.findFirstByEmployeeIdAndStatusIn(employee.getId(), ACTIVE).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This employee already has an active offboarding case");
    }
    OffboardingCase c = new OffboardingCase();
    c.setEmployeeId(employee.getId());
    c.setStatus(OffboardingStatus.PENDING_APPROVAL);
    c.setReason(req.reason());
    c.setLastWorkingDay(req.lastWorkingDay());
    c.setInitiatedByUserId(actor.userId());
    cases.save(c);

    // Durable trail for the HIERARCHY reviewer (its pending inbox is the primary surface); push follows.
    User hierarchy = hierarchyUser();
    if (hierarchy != null) {
      Notification n = new Notification();
      n.setRecipientUserId(hierarchy.getId());
      n.setType(NotificationType.OFFBOARDING_INITIATED);
      n.setEmployeeId(employee.getId());
      notifications.save(n);
    }
    audit.record(
        AuditActor.from(actor),
        "OFFBOARDING_INITIATED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("caseId", c.getId(), "lastWorkingDay", c.getLastWorkingDay().toString()),
        ip);
    return view(c);
  }

  /** HR cancels the active case (PENDING_APPROVAL or APPROVED), pre-completion. 409 if none is active. */
  @Transactional
  public OffboardingCaseView cancel(
      IhrmsPrincipal.User actor, String employeeId, CancelRequest req, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    OffboardingCase c =
        cases
            .findFirstByEmployeeIdAndStatusIn(employee.getId(), ACTIVE)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.CONFLICT, "There is no active offboarding case to cancel"));
    boolean wasPending = c.getStatus() == OffboardingStatus.PENDING_APPROVAL;
    c.setStatus(OffboardingStatus.CANCELLED);
    c.setCancelledByUserId(actor.userId());
    c.setCancelledAt(Instant.now());
    c.setCancelNote(req == null ? null : req.note());
    cases.save(c);
    audit.record(
        AuditActor.from(actor),
        "OFFBOARDING_CANCELLED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("caseId", c.getId(), "wasPending", wasPending),
        ip);
    return view(c);
  }

  /** The latest case for the HR record panel (record-view viewers). Null if the employee has no case. */
  @Transactional(readOnly = true)
  public OffboardingCaseView forRecord(IhrmsPrincipal.User actor, String employeeId) {
    authz.assertCanAccessEmployee(actor, employeeId); // same gating as the record read (§6)
    return cases.findFirstByEmployeeIdOrderByInitiatedAtDesc(employeeId).map(this::view).orElse(null);
  }

  /**
   * HR completes the offboarding (§3.6 stage 3) — the case must be APPROVED and every SENT document VERIFIED
   * (the letters are HR's judgment, not gated). One transaction: case → COMPLETED, Employee → OFFBOARDED
   * (login disabled, record retained). The employee + the team manager are notified after commit.
   */
  @Transactional
  public OffboardingCaseView complete(
      IhrmsPrincipal.User actor, String employeeId, String note, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    OffboardingCase c =
        cases
            .findFirstByEmployeeIdAndStatusIn(employeeId, List.of(OffboardingStatus.PENDING_APPROVAL, OffboardingStatus.APPROVED))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "No active offboarding case to complete"));
    if (c.getStatus() != OffboardingStatus.APPROVED) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "The offboarding case is not approved yet");
    }
    List<com.ihrms.domain.model.OffboardingDocument> sent = docs.findByCaseId(c.getId());
    if (sent.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Send and verify the offboarding documents before completing");
    }
    boolean allVerified =
        sent.stream().allMatch(d -> d.getStatus() == com.ihrms.domain.enums.OffboardingDocStatus.VERIFIED);
    if (!allVerified) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Every sent document must be verified before completing");
    }

    c.setStatus(OffboardingStatus.COMPLETED);
    c.setCompletedByUserId(actor.userId());
    c.setCompletedAt(Instant.now());
    c.setCompletionNote(note);
    cases.save(c);

    employee.setStatus(EmployeeStatus.OFFBOARDED);
    employees.save(employee);

    audit.record(
        AuditActor.from(actor),
        "OFFBOARDING_COMPLETED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("caseId", c.getId(), "note", note == null ? "" : note),
        ip);
    return view(c);
  }

  /**
   * HR deactivates an offboarded employee's account (§3.6) — the AUTH consequence, separate from completion:
   * once deactivated the employee cannot sign in through EITHER door and existing sessions die at the next
   * refresh. HR case-scope; allowed only once the employee is OFFBOARDED; idempotent-guarded (409 if already
   * deactivated). One-way in v1 — there is no reactivate. Audited ACCOUNT_DEACTIVATED. No employee notice
   * (they cannot see it).
   */
  @Transactional
  public void deactivate(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    if (employee.getStatus() != EmployeeStatus.OFFBOARDED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only an offboarded employee's account can be deactivated");
    }
    if (employee.isAccountDeactivated()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already deactivated");
    }
    employee.setAccountDeactivated(true);
    employee.setDeactivatedAt(Instant.now());
    employee.setDeactivatedByUserId(actor.userId());
    employees.save(employee);
    audit.record(
        AuditActor.from(actor),
        "ACCOUNT_DEACTIVATED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("employeeId", employee.getId()),
        ip);
  }

  /** Best-effort after complete commits: notify the employee (final) + the team manager. Never throws. */
  public void notifyAfterComplete(String employeeId) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null) {
        return;
      }
      String who = employee.getFullName() == null ? "The employee" : employee.getFullName();
      // Final notice to the (now offboarded) employee — they can no longer sign in, but a push/email still lands.
      mail.sendOffboardingCompleted(employee.getEmail(), employee.getFullName());
      push.sendToPrincipal(
          PrincipalRef.forEmployee(employee.getId(), employee.getCompanyId()),
          "Offboarding complete",
          "Your offboarding is complete. Thank you.",
          "/login");
      Team team =
          teams.findByCompanyIdAndHrUserId(employee.getCompanyId(), employee.getOnboardingHrId()).orElse(null);
      if (team != null && team.getManagerUserId() != null) {
        push.sendToPrincipal(
            PrincipalRef.forUser(team.getManagerUserId(), team.getCompanyId()),
            "Team member offboarded",
            who + " has been offboarded from " + team.getName() + ".",
            "/manager");
      }
    } catch (RuntimeException e) {
      log.warn("Post-completion notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- HIERARCHY: pending inbox + approve / reject --------------------------

  /** All PENDING_APPROVAL cases across all companies — the MINIMAL-PII inbox (charter loosening, §Offboarding). */
  @Transactional(readOnly = true)
  public List<HierarchyPendingRow> pending() {
    return cases.findByStatusOrderByInitiatedAtDesc(OffboardingStatus.PENDING_APPROVAL).stream()
        .map(this::pendingRow)
        .toList();
  }

  /** HIERARCHY approves a pending case. 409 unless PENDING_APPROVAL. */
  @Transactional
  public OffboardingDecisionResult approve(
      IhrmsPrincipal.User actor, String caseId, DecisionRequest req, String ip) {
    OffboardingCase c = decide(actor, caseId, OffboardingStatus.APPROVED, req == null ? null : req.note(), ip);
    return new OffboardingDecisionResult(c.getId(), c.getStatus());
  }

  /** HIERARCHY rejects a pending case (terminal). The note is required. 409 unless PENDING_APPROVAL. */
  @Transactional
  public OffboardingDecisionResult reject(
      IhrmsPrincipal.User actor, String caseId, DecisionRequest req, String ip) {
    if (req == null || req.note() == null || req.note().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required to reject");
    }
    OffboardingCase c = decide(actor, caseId, OffboardingStatus.REJECTED, req.note(), ip);
    return new OffboardingDecisionResult(c.getId(), c.getStatus());
  }

  private OffboardingCase decide(
      IhrmsPrincipal.User actor, String caseId, OffboardingStatus decision, String note, String ip) {
    OffboardingCase c =
        cases
            .findById(caseId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Offboarding case not found"));
    if (c.getStatus() != OffboardingStatus.PENDING_APPROVAL) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This case has already been decided");
    }
    c.setStatus(decision);
    c.setDecidedByUserId(actor.userId());
    c.setDecidedAt(Instant.now());
    c.setDecisionNote(note);
    cases.save(c);

    // Durable trail for the initiating HR (no bell feed → push + email follow after commit).
    Notification n = new Notification();
    n.setRecipientUserId(c.getInitiatedByUserId());
    n.setType(
        decision == OffboardingStatus.APPROVED
            ? NotificationType.OFFBOARDING_APPROVED
            : NotificationType.OFFBOARDING_REJECTED);
    n.setEmployeeId(c.getEmployeeId());
    notifications.save(n);

    audit.record(
        AuditActor.from(actor),
        decision == OffboardingStatus.APPROVED ? "OFFBOARDING_APPROVED" : "OFFBOARDING_REJECTED",
        "Employee",
        c.getEmployeeId(),
        Map.<String, Object>of("caseId", c.getId(), "note", note == null ? "" : note),
        ip);
    return c;
  }

  // --- controller-called, post-commit, best-effort -------------------------

  /** Push the HIERARCHY reviewer that a new case awaits (after initiate commits). Never throws. */
  public void notifyHierarchyAfterInitiate(String employeeId) {
    try {
      User hierarchy = hierarchyUser();
      Employee employee = employees.findById(employeeId).orElse(null);
      if (hierarchy == null || employee == null) {
        return;
      }
      String who = employee.getFullName() == null ? "An employee" : employee.getFullName();
      push.sendToPrincipal(
          PrincipalRef.forUser(hierarchy.getId(), null),
          "Offboarding approval needed",
          who + " has been submitted for offboarding approval.",
          "/hierarchy/offboarding");
    } catch (RuntimeException e) {
      log.warn("Post-initiate hierarchy push skipped (best-effort): {}", e.getMessage());
    }
  }

  /** Push the HIERARCHY reviewer that a pending case was withdrawn (after cancel commits). Never throws. */
  public void notifyHierarchyAfterCancel(String employeeId) {
    try {
      User hierarchy = hierarchyUser();
      Employee employee = employees.findById(employeeId).orElse(null);
      if (hierarchy == null || employee == null) {
        return;
      }
      String who = employee.getFullName() == null ? "An employee" : employee.getFullName();
      push.sendToPrincipal(
          PrincipalRef.forUser(hierarchy.getId(), null),
          "Offboarding withdrawn",
          who + "'s offboarding request was cancelled by HR.",
          "/hierarchy/offboarding");
    } catch (RuntimeException e) {
      log.warn("Post-cancel hierarchy push skipped (best-effort): {}", e.getMessage());
    }
  }

  /** Push + email the initiating HR of the hierarchy decision (after it commits). Never throws. */
  public void notifyHrAfterDecision(String caseId) {
    try {
      OffboardingCase c = cases.findById(caseId).orElse(null);
      if (c == null) {
        return;
      }
      User hr = users.findById(c.getInitiatedByUserId()).orElse(null);
      Employee employee = employees.findById(c.getEmployeeId()).orElse(null);
      if (hr == null || employee == null) {
        return;
      }
      boolean approved = c.getStatus() == OffboardingStatus.APPROVED;
      String who = employee.getFullName() == null ? "An employee" : employee.getFullName();
      push.sendToPrincipal(
          PrincipalRef.forUser(hr.getId(), employee.getCompanyId()),
          "Offboarding " + (approved ? "approved" : "rejected"),
          who + "'s offboarding was " + (approved ? "approved." : "rejected."),
          "/hr/employees/" + employee.getId());
      mail.sendOffboardingDecision(hr.getEmail(), who, approved, c.getDecisionNote());
    } catch (RuntimeException e) {
      log.warn("Post-decision HR notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- internals -----------------------------------------------------------

  private User hierarchyUser() {
    return users.findFirstByRoleOrderByCreatedAtAsc(UserRole.HIERARCHY).orElse(null);
  }

  private Employee loadOwn(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    boolean own =
        employee.getCompanyId().equals(actor.companyId())
            && employee.getOnboardingHrId().equals(actor.userId());
    if (!own) {
      // Anti-enumeration: a cross-HR/cross-company target is NOT_FOUND, matching the review workspace.
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
    }
    return employee;
  }

  private OffboardingCaseView view(OffboardingCase c) {
    boolean cancellable = ACTIVE.contains(c.getStatus());
    return new OffboardingCaseView(
        c.getId(),
        c.getStatus(),
        c.getReason(),
        c.getLastWorkingDay() == null ? null : c.getLastWorkingDay().toString(),
        userName(c.getInitiatedByUserId()),
        c.getInitiatedAt() == null ? null : c.getInitiatedAt().toString(),
        userName(c.getDecidedByUserId()),
        c.getDecidedAt() == null ? null : c.getDecidedAt().toString(),
        c.getDecisionNote(),
        userName(c.getCancelledByUserId()),
        c.getCancelledAt() == null ? null : c.getCancelledAt().toString(),
        c.getCancelNote(),
        userName(c.getCompletedByUserId()),
        c.getCompletedAt() == null ? null : c.getCompletedAt().toString(),
        c.getCompletionNote(),
        cancellable);
  }

  /** The MINIMAL-PII pending row — resolves ONLY name/code/company/team/reason/dates/initiator. */
  private HierarchyPendingRow pendingRow(OffboardingCase c) {
    Employee e = employees.findById(c.getEmployeeId()).orElse(null);
    String companyName =
        e == null ? null : companies.findById(e.getCompanyId()).map(Company::getName).orElse(null);
    String teamName =
        e == null
            ? null
            : teams
                .findByCompanyIdAndHrUserId(e.getCompanyId(), e.getOnboardingHrId())
                .map(Team::getName)
                .orElse(null);
    return new HierarchyPendingRow(
        c.getId(),
        e == null ? null : e.getFullName(),
        e == null ? null : e.getEmployeeCode(),
        companyName,
        teamName,
        c.getReason(),
        c.getLastWorkingDay() == null ? null : c.getLastWorkingDay().toString(),
        userName(c.getInitiatedByUserId()),
        c.getInitiatedAt() == null ? null : c.getInitiatedAt().toString());
  }

  private String userName(String userId) {
    return userId == null ? null : users.findById(userId).map(User::getName).orElse(null);
  }
}
