package com.ihrms.review;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.domain.support.EmployeeCodeService;
import com.ihrms.domain.support.EmployeeCodes;
import com.ihrms.onboarding.PdfService;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import com.ihrms.review.dto.ReviewDtos.ApproveRequest;
import com.ihrms.review.dto.ReviewDtos.DecisionResult;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RejectRequest;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import com.ihrms.review.dto.ReviewDtos.ReviewRequest;
import com.ihrms.storage.StorageService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

  /** Short-lived presigned-view TTL for the Form-2 PDF (matches the record assembler + offer PDF). */
  private static final int VIEW_TTL_SECONDS = 60;

  private final EmployeeRepository employees;
  private final Form1PersonalRepository form1s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final ApprovalRequestRepository approvals;
  private final NotificationRepository notifications;
  private final TeamRepository teams;
  private final UserRepository users;
  private final CompanyRepository companies;
  private final EmployeeCodeService codes;
  private final MailService mail;
  private final PdfService pdf;
  private final PushService push;
  private final AuthorizationService authz;
  private final AuditService audit;
  private final EmployeeRecordAssembler assembler;
  private final GeneratedDocumentRepository generated;
  private final StorageService storage;

  public ReviewService(
      EmployeeRepository employees,
      Form1PersonalRepository form1s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      ApprovalRequestRepository approvals,
      NotificationRepository notifications,
      TeamRepository teams,
      UserRepository users,
      CompanyRepository companies,
      EmployeeCodeService codes,
      MailService mail,
      PdfService pdf,
      PushService push,
      AuthorizationService authz,
      AuditService audit,
      EmployeeRecordAssembler assembler,
      GeneratedDocumentRepository generated,
      StorageService storage) {
    this.employees = employees;
    this.form1s = form1s;
    this.form3s = form3s;
    this.documents = documents;
    this.approvals = approvals;
    this.notifications = notifications;
    this.teams = teams;
    this.users = users;
    this.companies = companies;
    this.codes = codes;
    this.mail = mail;
    this.pdf = pdf;
    this.push = push;
    this.authz = authz;
    this.audit = audit;
    this.assembler = assembler;
    this.generated = generated;
    this.storage = storage;
  }

  /**
   * A short-lived presigned URL to the standalone Form-2 (Employee Info) PDF — the HR/SA-only artifact (§3.2).
   * Role-gated at the controller + URL rule to HR/COMPANY_ADMIN/SUPER_ADMIN (manager/accountant get 403); the
   * service additionally scopes access with {@code canAccessEmployee} and audits every view. The URL is NOT in
   * the shared record view (it reaches manager/accountant), so this is the only way to fetch it.
   */
  @Transactional(readOnly = true)
  public PresignedView form2PdfUrl(IhrmsPrincipal.User actor, String employeeId, String ip) {
    authz.assertCanAccessEmployee(actor, employeeId);
    GeneratedDocument doc =
        generated.findByEmployeeId(employeeId).stream()
            .filter(d -> d.getKind() == GeneratedDocumentKind.FORM2)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No Form 2 PDF yet"));
    audit.record(
        AuditActor.from(actor), "FORM2_PDF_VIEWED", "Employee", employeeId, Map.of("employeeId", employeeId), ip);
    return new PresignedView(storage.presignedGetUrl(doc.getStorageKey(), VIEW_TTL_SECONDS), VIEW_TTL_SECONDS);
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

  /**
   * HR APPROVES a verified employee (§3.3 — HR now holds the approval authority). Valid only while the
   * employee is {@code HR_VERIFIED} (409 otherwise). The team is NOT a choice — it is resolved server-side
   * as the employee's onboarding-HR's team (the same {@code findByCompanyIdAndHrUserId} resolution the leave
   * + document-request flows use), so the ApprovalRequest row and the manager notification can never name a
   * team the employee isn't in. MINTS the unique employee code (§5) exactly as the manager path did; records
   * the decision as an ApprovalRequest (created + decided now) so the hierarchy metrics + the Manager's
   * read-only onboarding history keep working; welcomes the employee by mail; and — when the team has a
   * manager — writes them a durable notification. The OS push + PDF regeneration run post-commit.
   */
  @Transactional
  public DecisionResult approve(
      IhrmsPrincipal.User actor, String employeeId, ApproveRequest req, String ip) {
    Employee employee = loadOwnById(actor, employeeId);
    requireVerified(employee);

    Team team = resolveTeam(employee);

    if (employee.getEmployeeCode() == null) {
      employee.setEmployeeCode(allocateCode(employee)); // §5 — the moment the employee ID is born
    }
    employee.setStatus(EmployeeStatus.APPROVED);
    employees.save(employee);

    // Decision record (created + decided now): keeps avgTimeToApproval (decidedAt − employee.createdAt),
    // approvedPerIstMonth and the Manager's read-only history working unchanged. managerUserId may be null
    // (a team without a manager); the row is still the "approved onto team X" record.
    ApprovalRequest decision = new ApprovalRequest();
    decision.setEmployeeId(employee.getId());
    decision.setHrUserId(actor.userId());
    decision.setManagerUserId(team.getManagerUserId());
    decision.setTeamId(team.getId());
    decision.setStatus(ApprovalStatus.APPROVED);
    decision.setNote(req.note());
    decision.setDecidedAt(Instant.now());
    approvals.save(decision);

    // The team's manager (if any) gets a durable bell entry that the employee joined their team; push follows.
    if (team.getManagerUserId() != null) {
      Notification n = new Notification();
      n.setRecipientUserId(team.getManagerUserId());
      n.setType(NotificationType.EMPLOYEE_APPROVED);
      n.setEmployeeId(employee.getId());
      notifications.save(n);
    }

    mail.sendEmployeeWelcome(employee.getEmail(), employee.getFullName(), employee.getEmployeeCode(), employee.getCompanyId());

    audit.record(
        AuditActor.from(actor),
        "HR_APPROVED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of(
            "employeeCode", employee.getEmployeeCode(),
            "teamId", team.getId(),
            "approvalRequestId", decision.getId()),
        ip);

    String managerName =
        team.getManagerUserId() == null
            ? null
            : users.findById(team.getManagerUserId()).map(User::getName).orElse(null);
    return new DecisionResult(
        employee.getEmployeeCode(), employee.getStatus(), team.getId(), team.getName(), managerName);
  }

  /** HR terminally REJECTS a verified application (§3.3). Valid only while HR_VERIFIED (409 otherwise). */
  @Transactional
  public DecisionResult reject(
      IhrmsPrincipal.User actor, String employeeId, RejectRequest req, String ip) {
    Employee employee = loadOwnById(actor, employeeId);
    requireVerified(employee);

    employee.setStatus(EmployeeStatus.REJECTED);
    employees.save(employee);

    // Terminal reject: the employee joined no team, so no ApprovalRequest / manager notification is written —
    // the decision is captured by the append-only audit event + the employee's REJECTED status.
    audit.record(
        AuditActor.from(actor),
        "HR_REJECTED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("note", req.note()),
        ip);
    return new DecisionResult(employee.getEmployeeCode(), employee.getStatus(), null, null, null);
  }

  /**
   * Best-effort OS push to the team's manager AFTER the approve transaction has committed (controller-called,
   * mirroring the leave/mail post-commit pattern) — a failure here never affects the committed approval.
   */
  public void pushApprovalToManager(String employeeId) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null) {
        return;
      }
      Team team =
          teams.findByCompanyIdAndHrUserId(employee.getCompanyId(), employee.getOnboardingHrId()).orElse(null);
      if (team == null || team.getManagerUserId() == null) {
        return;
      }
      String who = employee.getFullName() != null ? employee.getFullName() : "An employee";
      push.sendToPrincipal(
          PrincipalRef.forUser(team.getManagerUserId(), team.getCompanyId()),
          "New team member approved",
          who + " was approved onto " + team.getName() + ".",
          "/manager");
    } catch (RuntimeException e) {
      log.warn("Post-approval manager push skipped (best-effort): {}", e.getMessage());
    }
  }

  /**
   * The employee's team = their onboarding-HR's team (the system's scoping rule; the same resolution the
   * leave + document-request flows use). An employee's onboarding HR is always assigned to a team, but guard
   * defensively. Team is NOT chosen at approval — see §3.3/§5.
   */
  private Team resolveTeam(Employee employee) {
    return teams
        .findByCompanyIdAndHrUserId(employee.getCompanyId(), employee.getOnboardingHrId())
        .orElseThrow(() -> badRequest("The onboarding HR is not assigned to a team"));
  }

  /** Regenerate the employee's PDFs post-commit so the freshly-minted ID is stamped on them (best-effort). */
  public void regeneratePdfsQuietly(String employeeId) {
    try {
      employees.findById(employeeId).ifPresent(pdf::generateForEmployee);
    } catch (Exception e) {
      log.error("Post-approval PDF generation failed for employee {} (non-fatal)", employeeId, e);
    }
  }

  /** Allocate the next unique employee code for the employee's company (atomic, collision-safe, §5). */
  private String allocateCode(Employee employee) {
    Company company =
        companies.findById(employee.getCompanyId()).orElseThrow(() -> notFound("Company not found"));
    int sequence = codes.allocateSequence(employee.getCompanyId());
    if (sequence > EmployeeCodes.SEQ_MAX) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Employee ID sequence exhausted for this company");
    }
    return EmployeeCodes.format(company.getCode(), sequence);
  }

  /** The approve/reject decision is made from the verified/awaiting-HR-decision state (HR_VERIFIED). */
  private void requireVerified(Employee employee) {
    if (employee.getStatus() != EmployeeStatus.HR_VERIFIED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Employee is not awaiting an approval decision (status " + employee.getStatus() + ")");
    }
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

  /**
   * Review actions are allowed while the employee is under HR review — SUBMITTED, in revision, OR already
   * verified (HR may still send an item back from the verified state, which re-opens it for revision).
   * Not once APPROVED/REJECTED.
   */
  private void assertReviewable(Employee employee) {
    EmployeeStatus s = employee.getStatus();
    if (s != EmployeeStatus.SUBMITTED
        && s != EmployeeStatus.REVISION_REQUESTED
        && s != EmployeeStatus.HR_VERIFIED) {
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
   * The employee's overall status follows the items while under HR review: {@code REVISION_REQUESTED} if
   * any form/document is flagged; else {@code HR_VERIFIED} once EVERY item is verified (the auto-transition
   * that opens the HR approve/reject decision); else {@code SUBMITTED} (some items still unreviewed). Never
   * touches an APPROVED/REJECTED record. Keeps Verify ⇄ Send-back re-decidable and drives the decision gate.
   */
  private void recomputeReviewStatus(Employee employee) {
    EmployeeStatus s = employee.getStatus();
    if (s != EmployeeStatus.SUBMITTED
        && s != EmployeeStatus.REVISION_REQUESTED
        && s != EmployeeStatus.HR_VERIFIED) {
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
    EmployeeStatus target;
    if (anyRevision) {
      target = EmployeeStatus.REVISION_REQUESTED;
    } else if (assembler.reviewComplete(employee)) {
      target = EmployeeStatus.HR_VERIFIED; // all items verified — awaiting HR's approve/reject decision
    } else {
      target = EmployeeStatus.SUBMITTED;
    }
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
