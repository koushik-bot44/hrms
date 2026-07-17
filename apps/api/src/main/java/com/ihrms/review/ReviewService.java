package com.ihrms.review;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import com.ihrms.review.dto.ReviewDtos.ReviewRequest;
import com.ihrms.review.dto.ReviewDtos.RouteToManagerRequest;
import com.ihrms.review.dto.ReviewDtos.RouteToManagerResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * HR verification & routing (§3.3/§3.4). HR opens their own onboarded employee's record (the four
 * forms with sensitive values masked, the Form 4 uploads, and the generated PDFs), verifies/rejects
 * each form and document, optionally reveals sensitive values (an explicit, audited action), then
 * routes the completed record to the Manager on their team. Scope is centralized via
 * {@link AuthorizationService}; reads + actions are audited and partitioned by companyId.
 */
@Service
public class ReviewService {

  private final EmployeeRepository employees;
  private final Form1PersonalRepository form1s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final ApprovalRequestRepository approvals;
  private final NotificationRepository notifications;
  private final TeamRepository teams;
  private final UserRepository users;
  private final AuthorizationService authz;
  private final AuditService audit;
  private final EmployeeRecordAssembler assembler;

  public ReviewService(
      EmployeeRepository employees,
      Form1PersonalRepository form1s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      ApprovalRequestRepository approvals,
      NotificationRepository notifications,
      TeamRepository teams,
      UserRepository users,
      AuthorizationService authz,
      AuditService audit,
      EmployeeRecordAssembler assembler) {
    this.employees = employees;
    this.form1s = form1s;
    this.form3s = form3s;
    this.documents = documents;
    this.approvals = approvals;
    this.notifications = notifications;
    this.teams = teams;
    this.users = users;
    this.authz = authz;
    this.audit = audit;
    this.assembler = assembler;
  }

  /** Open a record by INTERNAL id — the verification entry (pre-approval employees have no code). */
  public EmployeeRecordView getRecord(IhrmsPrincipal.User actor, String employeeId, String ip) {
    return viewRecord(actor, loadOwnById(actor, employeeId), ip);
  }

  /** §3.4 records lookup by employee code (post-approval only). Read-only; audited. */
  public EmployeeRecordView lookupByCode(IhrmsPrincipal.User actor, String employeeCode, String ip) {
    return viewRecord(actor, loadOwnByCode(actor, employeeCode), ip);
  }

  private EmployeeRecordView viewRecord(IhrmsPrincipal.User actor, Employee employee, String ip) {
    audit.record(
        AuditActor.from(actor),
        "EMPLOYEE_RECORD_VIEWED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("email", employee.getEmail()),
        ip);
    return assembler.build(employee);
  }

  /** Reveal the plaintext sensitive values — an explicit, audited action (§6). */
  public RevealedSensitive reveal(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadOwnById(actor, employeeId);
    audit.record(
        AuditActor.from(actor),
        "SENSITIVE_FIELD_REVEALED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("email", employee.getEmail()),
        ip);
    return assembler.reveal(employee);
  }

  /**
   * Per-item review of a whole form (FORM1 / FORM2 / FORM3): Verify, or Send back for revision
   * (§3.3). {@code REVISION_REQUESTED} requires a note; VERIFIED/REJECTED clear any prior note.
   * Decisions are re-decidable while the employee is under review; the employee's overall status is
   * recomputed from the items afterwards.
   */
  @Transactional
  public EmployeeRecordView reviewForm(
      IhrmsPrincipal.User actor, String employeeId, String formParam, ReviewRequest req, String ip) {
    Employee employee = loadOwnById(actor, employeeId);
    assertReviewable(employee);
    SectionStatus decision = parseSectionDecision(req);
    String form = formParam == null ? "" : formParam.toUpperCase();

    switch (form) {
      case "FORM1" -> {
        Form1Personal f1 =
            form1s.findByEmployeeId(employee.getId()).orElseThrow(() -> notFound("Form 1 not found"));
        applyDecision(f1, decision, req);
        form1s.save(f1);
        audit.record(
            AuditActor.from(actor), "FORM_REVIEWED", "Form1Personal", f1.getId(),
            reviewMeta("form", "FORM1", req), ip);
      }
      case "FORM2" ->
          // Form 2 is HR/SA-authored at onboard (§3.2) — it is not a verifiable review item.
          throw badRequest("Form 2 is filled by HR and is not part of verification");
      case "FORM3" -> {
        List<Form3PrevEmployment> rows =
            form3s.findByEmployeeIdOrderByOrderIndexAsc(employee.getId());
        if (rows.isEmpty()) {
          throw badRequest("There is no Form 3 to review");
        }
        rows.forEach(r -> applyDecision(r, decision, req));
        form3s.saveAll(rows);
        audit.record(
            AuditActor.from(actor), "FORM_REVIEWED", "Employee", employee.getId(),
            reviewMeta("form", "FORM3", req), ip);
      }
      default -> throw badRequest("Unknown form \"" + formParam + "\"");
    }
    recomputeReviewStatus(employee);
    return assembler.build(employee);
  }

  @Transactional
  public EmployeeRecordView reviewDocument(
      IhrmsPrincipal.User actor, String employeeId, String documentId, ReviewRequest req, String ip) {
    Employee employee = loadOwnById(actor, employeeId);
    assertReviewable(employee);
    Document doc =
        documents
            .findByIdAndEmployeeId(documentId, employee.getId())
            .orElseThrow(() -> notFound("Document not found"));

    DocumentStatus decision = parseDocumentDecision(req);
    doc.setStatus(decision);
    if (decision == DocumentStatus.REVISION_REQUESTED) {
      doc.setRevisionNote(req.reason());
      doc.setRevisionRequestedAt(Instant.now());
    } else {
      doc.setRevisionNote(null);
      doc.setRevisionRequestedAt(null);
    }
    documents.save(doc);

    audit.record(
        AuditActor.from(actor), "DOCUMENT_REVIEWED", "Document", doc.getId(),
        reviewMeta("docType", doc.getDocType().name(), req), ip);
    recomputeReviewStatus(employee);
    return assembler.build(employee);
  }

  /** §3.3 step 2: route the completed review to the Manager on the HR's team. */
  @Transactional
  public RouteToManagerResult routeToManager(
      IhrmsPrincipal.User actor, String employeeId, RouteToManagerRequest req, String ip) {
    Employee employee = loadOwnById(actor, employeeId);
    assertReviewable(employee);

    if (!assembler.reviewComplete(employee)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Verify every form and document before routing for approval");
    }

    Team team =
        teams
            .findByCompanyIdAndHrUserId(actor.companyId(), actor.userId())
            .orElseThrow(() -> badRequest("You are not assigned to a team"));
    String managerUserId = team.getManagerUserId();
    if (managerUserId == null) {
      throw badRequest("Your team has no manager assigned yet");
    }

    ApprovalRequest approval = new ApprovalRequest();
    approval.setEmployeeId(employee.getId());
    approval.setHrUserId(actor.userId());
    approval.setManagerUserId(managerUserId);
    approval.setTeamId(team.getId());
    approval.setStatus(ApprovalStatus.PENDING);
    approval.setNote(req == null ? null : req.note());
    approvals.save(approval);

    Notification notification = new Notification();
    notification.setRecipientUserId(managerUserId);
    notification.setType(NotificationType.APPROVAL_REQUESTED);
    notification.setEmployeeId(employee.getId());
    notifications.save(notification);

    employee.setStatus(EmployeeStatus.HR_VERIFIED);
    employees.save(employee);

    audit.record(
        AuditActor.from(actor),
        "APPROVAL_ROUTED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of(
            "approvalRequestId", approval.getId(),
            "managerUserId", managerUserId,
            "teamId", team.getId()),
        ip);

    String managerName = users.findById(managerUserId).map(User::getName).orElse(null);
    return new RouteToManagerResult(
        employee.getEmployeeCode(), employee.getStatus(), approval.getId(), managerName);
  }

  // --- internals ------------------------------------------------------------

  private Employee loadOwnById(IhrmsPrincipal.User actor, String employeeId) {
    return assertOwn(
        actor, employees.findById(employeeId).orElseThrow(() -> notFound("Employee not found")));
  }

  private Employee loadOwnByCode(IhrmsPrincipal.User actor, String employeeCode) {
    return assertOwn(
        actor,
        employees.findByEmployeeCode(employeeCode).orElseThrow(() -> notFound("Employee not found")));
  }

  private Employee assertOwn(IhrmsPrincipal.User actor, Employee employee) {
    AuthorizationService.EmployeeScope scope =
        new AuthorizationService.EmployeeScope(
            employee.getId(),
            employee.getCompanyId(),
            employee.getOnboardingHrId(),
            employee.getEmployeeCode());
    if (!authz.canAccessEmployee(actor, scope)) {
      throw notFound("Employee not found");
    }
    return employee;
  }

  /** Review actions are allowed while the employee is under HR review — before routing/approval. */
  private void assertReviewable(Employee employee) {
    EmployeeStatus s = employee.getStatus();
    if (s != EmployeeStatus.SUBMITTED && s != EmployeeStatus.REVISION_REQUESTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Employee is not awaiting review (status " + s + ")");
    }
  }

  /** {@code REVISION_REQUESTED} needs a note (the change request shown to the employee, §3.3). */
  private SectionStatus parseSectionDecision(ReviewRequest req) {
    SectionStatus decision = SectionStatus.valueOf(req.decision());
    requireNoteForRevision(decision == SectionStatus.REVISION_REQUESTED, req);
    return decision;
  }

  private DocumentStatus parseDocumentDecision(ReviewRequest req) {
    DocumentStatus decision = DocumentStatus.valueOf(req.decision());
    requireNoteForRevision(decision == DocumentStatus.REVISION_REQUESTED, req);
    return decision;
  }

  private void requireNoteForRevision(boolean isRevision, ReviewRequest req) {
    if (isRevision && (req.reason() == null || req.reason().isBlank())) {
      throw badRequest("Add a note describing the change when sending an item back for revision");
    }
  }

  private void applyDecision(Form1Personal f, SectionStatus decision, ReviewRequest req) {
    f.setStatus(decision);
    f.setRevisionNote(decision == SectionStatus.REVISION_REQUESTED ? req.reason() : null);
    f.setRevisionRequestedAt(decision == SectionStatus.REVISION_REQUESTED ? Instant.now() : null);
  }

  private void applyDecision(Form3PrevEmployment f, SectionStatus decision, ReviewRequest req) {
    f.setStatus(decision);
    f.setRevisionNote(decision == SectionStatus.REVISION_REQUESTED ? req.reason() : null);
    f.setRevisionRequestedAt(decision == SectionStatus.REVISION_REQUESTED ? Instant.now() : null);
  }

  /**
   * The employee's overall status follows the items while under HR review: {@code REVISION_REQUESTED}
   * if any form/document is flagged, otherwise {@code SUBMITTED}. Never touches a routed/approved
   * record. This keeps Verify ⇄ Send-back re-decidable and the routing gate consistent.
   */
  private void recomputeReviewStatus(Employee employee) {
    EmployeeStatus s = employee.getStatus();
    if (s != EmployeeStatus.SUBMITTED && s != EmployeeStatus.REVISION_REQUESTED) {
      return;
    }
    String id = employee.getId();
    // Form 2 is HR/SA-authored (§3.2) — never sent back, so it never drives the revision state.
    boolean anyRevision =
        form1s.findByEmployeeId(id).map(f -> f.getStatus() == SectionStatus.REVISION_REQUESTED).orElse(false)
            || form3s.findByEmployeeIdOrderByOrderIndexAsc(id).stream()
                .anyMatch(r -> r.getStatus() == SectionStatus.REVISION_REQUESTED)
            || documents.findByEmployeeId(id).stream()
                .anyMatch(d -> d.getStatus() == DocumentStatus.REVISION_REQUESTED);
    EmployeeStatus target = anyRevision ? EmployeeStatus.REVISION_REQUESTED : EmployeeStatus.SUBMITTED;
    if (target != s) {
      employee.setStatus(target);
      employees.save(employee);
    }
  }

  private static Map<String, Object> reviewMeta(String idKey, String idValue, ReviewRequest req) {
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put(idKey, idValue);
    meta.put("decision", req.decision());
    if (req.reason() != null && !req.reason().isBlank()) {
      meta.put("reason", req.reason());
    }
    return meta;
  }

  private ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
