package com.ihrms.review;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.ProfileSection;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.ProfileSectionRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RecordDocument;
import com.ihrms.review.dto.ReviewDtos.RecordSection;
import com.ihrms.review.dto.ReviewDtos.ReviewRequest;
import com.ihrms.review.dto.ReviewDtos.RouteToManagerRequest;
import com.ihrms.review.dto.ReviewDtos.RouteToManagerResult;
import com.ihrms.storage.StorageService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * HR verification & routing (ARCHITECTURE.md §3.3/§3.4). HR looks up their own onboarded employee
 * by code, reviews each section/document (verify/reject), then routes the record to the Manager on
 * their team. Scope is centralized via {@link AuthorizationService}; reads + actions are audited and
 * partitioned by companyId; documents are reached only through short-lived presigned GET URLs.
 */
@Service
public class ReviewService {

  private static final int VIEW_TTL_SECONDS = 60; // presigned GET (short-lived, audited download)

  private final EmployeeRepository employees;
  private final ProfileSectionRepository sections;
  private final DocumentRepository documents;
  private final ApprovalRequestRepository approvals;
  private final NotificationRepository notifications;
  private final TeamRepository teams;
  private final UserRepository users;
  private final AuthorizationService authz;
  private final AuditService audit;
  private final StorageService storage;

  public ReviewService(
      EmployeeRepository employees,
      ProfileSectionRepository sections,
      DocumentRepository documents,
      ApprovalRequestRepository approvals,
      NotificationRepository notifications,
      TeamRepository teams,
      UserRepository users,
      AuthorizationService authz,
      AuditService audit,
      StorageService storage) {
    this.employees = employees;
    this.sections = sections;
    this.documents = documents;
    this.approvals = approvals;
    this.notifications = notifications;
    this.teams = teams;
    this.users = users;
    this.authz = authz;
    this.audit = audit;
    this.storage = storage;
  }

  /** §3.4 lookup: the full record (sections + documents with presigned URLs). Sensitive read -> audited. */
  public EmployeeRecordView getRecord(IhrmsPrincipal.User actor, String employeeCode, String ip) {
    Employee employee = loadOwnEmployee(actor, employeeCode);
    audit.record(
        AuditActor.from(actor),
        "EMPLOYEE_RECORD_VIEWED",
        "Employee",
        employee.getId(),
        Map.of("employeeCode", employee.getEmployeeCode()),
        ip);
    return record(employee);
  }

  public EmployeeRecordView reviewSection(
      IhrmsPrincipal.User actor, String employeeCode, String keyParam, ReviewRequest req, String ip) {
    Employee employee = loadOwnEmployee(actor, employeeCode);
    assertReviewable(employee);
    SectionKey key = parseKey(keyParam);
    ProfileSection section =
        sections
            .findByEmployeeIdAndKey(employee.getId(), key)
            .orElseThrow(() -> notFound("Section not found"));

    section.setStatus(SectionStatus.valueOf(req.decision()));
    sections.save(section);

    audit.record(
        AuditActor.from(actor),
        "SECTION_REVIEWED",
        "ProfileSection",
        section.getId(),
        reviewMeta("key", key.name(), req),
        ip);
    return record(employee);
  }

  public EmployeeRecordView reviewDocument(
      IhrmsPrincipal.User actor, String employeeCode, String documentId, ReviewRequest req, String ip) {
    Employee employee = loadOwnEmployee(actor, employeeCode);
    assertReviewable(employee);
    Document doc =
        documents
            .findByIdAndEmployeeId(documentId, employee.getId())
            .orElseThrow(() -> notFound("Document not found"));

    doc.setStatus(DocumentStatus.valueOf(req.decision()));
    documents.save(doc);

    audit.record(
        AuditActor.from(actor),
        "DOCUMENT_REVIEWED",
        "Document",
        doc.getId(),
        reviewMeta("docType", doc.getDocType().name(), req),
        ip);
    return record(employee);
  }

  /** §3.3 step 2: route the completed review to the Manager on the HR's team. */
  @Transactional
  public RouteToManagerResult routeToManager(
      IhrmsPrincipal.User actor, String employeeCode, RouteToManagerRequest req, String ip) {
    Employee employee = loadOwnEmployee(actor, employeeCode);
    assertReviewable(employee);

    List<ProfileSection> mySections = sections.findByEmployeeId(employee.getId());
    List<Document> myDocuments =
        documents.findByEmployeeId(employee.getId()).stream()
            .filter(d -> d.getStatus() != DocumentStatus.PENDING)
            .toList();
    if (!isReviewComplete(employee, mySections, myDocuments)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Verify every section and document before routing for approval");
    }

    // The approver is the Manager on the acting HR's team (§2).
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

  private EmployeeRecordView record(Employee employee) {
    List<ProfileSection> secs = sections.findByEmployeeIdOrderByKeyAsc(employee.getId());
    List<Document> docs =
        documents.findByEmployeeIdOrderByUploadedAtDesc(employee.getId()).stream()
            .filter(d -> d.getStatus() != DocumentStatus.PENDING)
            .toList();
    boolean complete = isReviewComplete(employee, secs, docs);
    return new EmployeeRecordView(
        employee.getEmployeeCode(),
        employee.getEmail(),
        employee.getStatus(),
        complete,
        secs.stream().map(ReviewService::sectionView).toList(),
        docs.stream().map(this::documentView).toList());
  }

  private boolean isReviewComplete(
      Employee employee, List<ProfileSection> secs, List<Document> docs) {
    return employee.getStatus() == EmployeeStatus.SUBMITTED
        && !secs.isEmpty()
        && secs.stream().allMatch(s -> s.getStatus() == SectionStatus.VERIFIED)
        && docs.stream().allMatch(d -> d.getStatus() == DocumentStatus.VERIFIED);
  }

  private static RecordSection sectionView(ProfileSection s) {
    return new RecordSection(
        s.getKey(),
        s.getData() == null ? Map.of() : s.getData(),
        s.getStatus(),
        s.getUpdatedAt().toString());
  }

  private RecordDocument documentView(Document d) {
    return new RecordDocument(
        d.getId(),
        d.getSectionKey(),
        d.getDocType(),
        d.getFileName(),
        d.getMimeType(),
        d.getSha256(),
        d.getStatus(),
        d.getUploadedAt().toString(),
        storage.presignedGetUrl(d.getStorageKey(), VIEW_TTL_SECONDS));
  }

  /** Resolve the employee by code and confirm it is the acting HR's own (404 otherwise — no leak). */
  private Employee loadOwnEmployee(IhrmsPrincipal.User actor, String employeeCode) {
    Employee employee =
        employees
            .findByEmployeeCode(employeeCode)
            .orElseThrow(() -> notFound("Employee not found"));
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

  private void assertReviewable(Employee employee) {
    if (employee.getStatus() != EmployeeStatus.SUBMITTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Employee is not awaiting review (status " + employee.getStatus() + ")");
    }
  }

  private SectionKey parseKey(String keyParam) {
    try {
      return SectionKey.valueOf(keyParam);
    } catch (IllegalArgumentException e) {
      throw badRequest("Unknown section \"" + keyParam + "\"");
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
