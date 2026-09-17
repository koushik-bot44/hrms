package com.ihrms.onboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.OnboardingType;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Signature;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.SignatureRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.onboarding.dto.OnboardingDtos;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentUploadRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3Request;
import com.ihrms.onboarding.dto.OnboardingDtos.OnboardingDashboard;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedUpload;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.onboarding.dto.OnboardingDtos.SignatureRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.SignatureView;
import com.ihrms.review.dto.ReviewDtos.DecisionResult;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * HR enters an EXISTING employee's record (§3.2). Someone already on the payroll never signs in to onboard
 * and gets no email: HR fills Forms 1/3/4, uploads a scan of the employee's signature, and APPROVES directly
 * — no verify step, since HR entered the data. There is no offer gate on this surface (existing employees
 * have no offer), every write is audited with the HR actor, and approval KEEPS the employee ID HR typed on
 * Form 2 (copied onto {@code employeeCode} inside the approve transaction — never minted). Mirrors the
 * employee's own {@link OnboardingService} write semantics so the record it produces is indistinguishable
 * downstream (same statuses, storage layout, PDFs).
 */
@Service
public class HrOnboardingService {

  private static final Logger log = LoggerFactory.getLogger(HrOnboardingService.class);

  private static final int UPLOAD_TTL_SECONDS = 300;
  private static final int VIEW_TTL_SECONDS = 60;

  /** The per-employment Form 4 slots that carry a groupIndex (1..4) — same set as the employee surface. */
  private static final Set<DocumentType> GROUPED =
      EnumSet.of(
          DocumentType.OFFER_OR_APPOINTMENT_LETTER,
          DocumentType.HIKE_LETTER,
          DocumentType.RELIEVING_LETTER);

  private final EmployeeRepository employees;
  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final SignatureRepository signatures;
  private final TeamRepository teams;
  private final ApprovalRequestRepository approvals;
  private final NotificationRepository notifications;
  private final UserRepository users;
  private final StorageService storage;
  private final OnboardingService onboarding;
  private final PdfService pdf;
  private final AuthorizationService authz;
  private final AuditService audit;

  public HrOnboardingService(
      EmployeeRepository employees,
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      SignatureRepository signatures,
      TeamRepository teams,
      ApprovalRequestRepository approvals,
      NotificationRepository notifications,
      UserRepository users,
      StorageService storage,
      OnboardingService onboarding,
      PdfService pdf,
      AuthorizationService authz,
      AuditService audit) {
    this.employees = employees;
    this.form1s = form1s;
    this.form2s = form2s;
    this.form3s = form3s;
    this.documents = documents;
    this.signatures = signatures;
    this.teams = teams;
    this.approvals = approvals;
    this.notifications = notifications;
    this.users = users;
    this.storage = storage;
    this.onboarding = onboarding;
    this.pdf = pdf;
    this.authz = authz;
    this.audit = audit;
  }

  // --- reads ------------------------------------------------------------------

  /**
   * The record's onboarding data for HR entry — the same shape the employee's own dashboard uses. Locked
   * once approved (like every write): this surface returns Form 1's sensitive values in PLAIN, which is
   * right while HR is typing them but would bypass the masked-record + audited-reveal invariant (§6)
   * afterwards. Post-approval reads go through /employees/{id}/record.
   */
  public OnboardingDashboard dashboard(IhrmsPrincipal.User actor, String employeeId) {
    return onboarding.dashboardOf(loadForEntry(actor, employeeId, true));
  }

  public PresignedView documentViewUrl(IhrmsPrincipal.User actor, String employeeId, String documentId, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);
    Document doc = loadDocument(employee.getId(), documentId);
    String url = storage.presignedGetUrl(doc.getStorageKey(), VIEW_TTL_SECONDS);
    audit(actor, employee, "DOCUMENT_VIEWED", "Document", documentId,
        Map.of("docType", doc.getDocType().name()), ip);
    return new PresignedView(url, VIEW_TTL_SECONDS);
  }

  // --- writes (Forms 1/3, documents, signature) --------------------------------

  @Transactional
  public Form1View saveForm1(IhrmsPrincipal.User actor, String employeeId, Form1Request body, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);
    Form1Personal f1 = form1s.findByEmployeeId(employeeId).orElse(null);
    if (f1 == null) {
      f1 = new Form1Personal();
      f1.setEmployeeId(employeeId);
    }
    f1.setData(FormMappers.toForm1Data(body, f1.getData()));
    f1.setRevisionNote(null);
    f1.setRevisionRequestedAt(null);
    f1.setStatus(SectionStatus.DRAFT);
    form1s.save(f1);

    // Same write-through as the employee surface (§3.2): the relocated fields live on form2_info.
    Form2Info f2 = form2s.findByEmployeeId(employeeId).orElse(null);
    if (f2 == null) {
      f2 = new Form2Info();
      f2.setEmployeeId(employeeId);
    }
    FormMappers.applyForm1RelocatedFields(f2, body);
    form2s.save(f2);
    markInProgress(employee);

    audit(actor, employee, "FORM1_SAVED", "Form1Personal", f1.getId(), null, ip);
    return FormMappers.form1View(f1, f2, FormMappers.Mode.PLAIN);
  }

  @Transactional
  public List<Form3EntryView> saveForm3(
      IhrmsPrincipal.User actor, String employeeId, Form3Request body, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);
    List<Form3PrevEmployment> current = form3s.findByEmployeeIdOrderByOrderIndexAsc(employeeId);
    form3s.deleteAll(current);
    int index = 0;
    if (body.entries() != null) {
      for (var entry : body.entries()) {
        Form3PrevEmployment row = new Form3PrevEmployment();
        row.setEmployeeId(employeeId);
        row.setStatus(SectionStatus.DRAFT);
        FormMappers.applyForm3(row, entry, index++);
        form3s.save(row);
      }
    }
    markInProgress(employee);
    audit(actor, employee, "FORM3_SAVED", "Employee", employeeId, Map.of("entries", index), ip);
    return form3s.findByEmployeeIdOrderByOrderIndexAsc(employeeId).stream()
        .map(e -> FormMappers.form3View(e, FormMappers.Mode.PLAIN))
        .toList();
  }

  public PresignedUpload requestUpload(
      IhrmsPrincipal.User actor, String employeeId, DocumentUploadRequest body, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);

    Integer groupIndex = normalizeGroupIndex(body.docType(), body.groupIndex());
    int existing =
        documents.countByEmployeeIdAndDocTypeAndGroupIndex(employeeId, body.docType(), groupIndex);
    int max = OnboardingDtos.maxDocumentsForType(body.docType());
    if (existing >= max) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "You can upload at most "
              + max
              + (max == 1 ? " file" : " files")
              + " for this document. Remove one to add another.");
    }

    String storageKey =
        storage.buildKey(employee.getCompanyId(), employeeId, "form4", body.fileName());
    Document doc = new Document();
    doc.setEmployeeId(employeeId);
    doc.setDocType(body.docType());
    doc.setGroupIndex(groupIndex);
    doc.setFileName(body.fileName());
    doc.setStorageKey(storageKey);
    doc.setMimeType(body.mimeType());
    doc.setStatus(DocumentStatus.PENDING);
    documents.save(doc);

    String uploadUrl = storage.presignedPutUrl(storageKey, body.mimeType(), UPLOAD_TTL_SECONDS);
    markInProgress(employee);

    audit(actor, employee, "DOCUMENT_UPLOAD_REQUESTED", "Document", doc.getId(),
        Map.of("docType", body.docType().name(), "fileName", body.fileName()), ip);
    return new PresignedUpload(
        doc.getId(), uploadUrl, "PUT", Map.of("Content-Type", body.mimeType()), UPLOAD_TTL_SECONDS);
  }

  public DocumentView confirmUpload(
      IhrmsPrincipal.User actor, String employeeId, String documentId, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);
    Document doc = loadDocument(employeeId, documentId);

    byte[] bytes = storage.getObjectBytes(doc.getStorageKey());
    String sha256 = Hashing.sha256Hex(bytes);
    doc.setSha256(sha256);
    doc.setStatus(DocumentStatus.UPLOADED);
    doc.setRevisionNote(null);
    doc.setRevisionRequestedAt(null);
    documents.save(doc);

    audit(actor, employee, "DOCUMENT_UPLOADED", "Document", documentId,
        Map.of("docType", doc.getDocType().name(), "sha256", sha256, "sizeBytes", bytes.length), ip);
    return new DocumentView(
        doc.getId(),
        doc.getDocType(),
        doc.getGroupIndex(),
        doc.getFileName(),
        doc.getMimeType(),
        doc.getSha256(),
        doc.getStatus(),
        doc.getRevisionNote(),
        doc.getUploadedAt().toString());
  }

  public OnboardingDashboard deleteDocument(
      IhrmsPrincipal.User actor, String employeeId, String documentId, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);
    Document doc = loadDocument(employeeId, documentId);

    documents.delete(doc);
    try {
      storage.delete(doc.getStorageKey());
    } catch (RuntimeException e) {
      log.warn("Could not delete stored bytes for document {}: {}", documentId, e.getMessage());
    }
    audit(actor, employee, "DOCUMENT_DELETED", "Document", documentId,
        Map.of("docType", doc.getDocType().name(), "fileName", doc.getFileName()), ip);
    return onboarding.dashboardOf(employee);
  }

  /** HR uploads a SCAN of the employee's own signature — stored exactly like a self-adopted one (§3.2). */
  @Transactional
  public SignatureView saveSignature(
      IhrmsPrincipal.User actor, String employeeId, SignatureRequest body, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);

    byte[] bytes;
    String contentType;
    try {
      int comma = body.imageDataUrl().indexOf(',');
      String meta = body.imageDataUrl().substring("data:".length(), comma); // e.g. image/png;base64
      contentType = meta.split(";")[0];
      bytes = Base64.getDecoder().decode(body.imageDataUrl().substring(comma + 1));
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the signature image");
    }
    if (bytes.length == 0 || bytes.length > OnboardingDtos.MAX_UPLOAD_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature image is empty or too large");
    }

    String key = storage.buildKey(employee.getCompanyId(), employeeId, "signature", "signature.png");
    Signature signature = signatures.findByEmployeeId(employeeId).orElse(null);
    if (signature != null && !signature.getStorageKey().equals(key)) {
      try {
        storage.delete(signature.getStorageKey());
      } catch (RuntimeException e) {
        log.warn("Could not delete old signature for {}: {}", employeeId, e.getMessage());
      }
    }
    if (signature == null) {
      signature = new Signature();
      signature.setEmployeeId(employeeId);
    }
    storage.putObject(key, bytes, contentType);
    signature.setStorageKey(key);
    signature.setType(body.type());
    signature.setSignedAt(Instant.now());
    signatures.save(signature);
    markInProgress(employee);

    audit(actor, employee, "SIGNATURE_CAPTURED", "Signature", signature.getId(),
        Map.of("type", body.type(), "source", "HR_SCAN"), ip);
    return new SignatureView(signature.getType(), signature.getSignedAt().toString());
  }

  // --- approve ------------------------------------------------------------------

  /**
   * HR approves the existing employee once the record is complete (§3.2): the same completeness bar as the
   * employee's submit, then — in one transaction — every section/document flips to VERIFIED, the ID HR
   * entered on Form 2 becomes {@code employeeCode} (re-checked for uniqueness; never minted), the status
   * becomes APPROVED, the decision lands as an ApprovalRequest (metrics + Manager history), and the team's
   * manager gets a bell entry. NO email is sent. PDFs regenerate post-commit (controller).
   */
  @Transactional
  public DecisionResult approve(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadForEntry(actor, employeeId, true);

    Form1Personal f1 = form1s.findByEmployeeId(employeeId).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employeeId).orElse(null);
    List<Document> docs = documents.findByEmployeeId(employeeId);
    Signature signature = signatures.findByEmployeeId(employeeId).orElse(null);

    List<String> missing =
        OnboardingCompleteness.missing(f1, f2, docs, signature, employee.isItrRequired());
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Complete the record before approving: " + missing.get(0));
    }

    String enteredId =
        f2 == null || f2.getData() == null ? null : (String) f2.getData().get("employeeId");
    if (enteredId == null || enteredId.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Enter their employee ID (Edit Employee Info) before approving");
    }
    if (employees.enteredEmployeeIdInUse(enteredId, employeeId)) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This employee ID is already in use");
    }

    Team team = resolveTeam(employee);

    // HR entered the data, so approval verifies it in the same action (no separate verify step).
    f1.setStatus(SectionStatus.VERIFIED);
    form1s.save(f1);
    List<Form3PrevEmployment> f3 = form3s.findByEmployeeIdOrderByOrderIndexAsc(employeeId);
    f3.forEach(r -> r.setStatus(SectionStatus.VERIFIED));
    form3s.saveAll(f3);
    f2.setStatus(SectionStatus.VERIFIED); // HR-authored + HR-entered — verified in the same action
    form2s.save(f2);
    docs.stream()
        .filter(d -> d.getStatus() == DocumentStatus.UPLOADED)
        .forEach(d -> d.setStatus(DocumentStatus.VERIFIED));
    documents.saveAll(docs);

    employee.setEmployeeCode(enteredId); // §3.2 — the kept ID becomes the code, never minted
    employee.setStatus(EmployeeStatus.APPROVED);
    try {
      employees.saveAndFlush(employee); // the unique index backstops a concurrent claim of the same ID
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This employee ID is already in use");
    }

    ApprovalRequest decision = new ApprovalRequest();
    decision.setEmployeeId(employee.getId());
    decision.setHrUserId(actor.userId());
    decision.setManagerUserId(team.getManagerUserId());
    decision.setTeamId(team.getId());
    decision.setStatus(ApprovalStatus.APPROVED);
    decision.setDecidedAt(Instant.now());
    approvals.save(decision);

    if (team.getManagerUserId() != null) {
      Notification n = new Notification();
      n.setRecipientUserId(team.getManagerUserId());
      n.setType(NotificationType.EMPLOYEE_APPROVED);
      n.setEmployeeId(employee.getId());
      notifications.save(n);
    }

    // Deliberately NO mail: an existing employee gets no welcome/credentials email from onboarding (§3.2).

    audit(actor, employee, "HR_APPROVED", "Employee", employee.getId(),
        Map.of(
            "employeeCode", employee.getEmployeeCode(),
            "teamId", team.getId(),
            "approvalRequestId", decision.getId(),
            "onboardingType", OnboardingType.EXISTING_EMPLOYEE.name()),
        ip);

    String managerName =
        team.getManagerUserId() == null
            ? null
            : users.findById(team.getManagerUserId()).map(User::getName).orElse(null);
    return new DecisionResult(
        employee.getEmployeeCode(), employee.getStatus(), team.getId(), team.getName(), managerName);
  }

  /** Regenerate the record's PDFs post-commit so the kept ID is stamped on them (best-effort). */
  public void regeneratePdfsQuietly(String employeeId) {
    try {
      employees.findById(employeeId).ifPresent(pdf::generateForEmployee);
    } catch (Exception e) {
      log.error("Post-approval PDF generation failed for employee {} (non-fatal)", employeeId, e);
    }
  }

  // --- internals ------------------------------------------------------------------

  /**
   * Load the record for HR entry: 404 unless the actor may access it (HR own-onboarded — the URL rule +
   * {@code @PreAuthorize} already restrict the surface to HR), 409 unless it is an EXISTING employee, and
   * 409 once approved — reads included, because this surface is PLAIN-mode (§6); the approved record is
   * viewed through the masked /employees/{id}/record instead.
   */
  private Employee loadForEntry(IhrmsPrincipal.User actor, String employeeId, boolean write) {
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    AuthorizationService.EmployeeScope scope =
        new AuthorizationService.EmployeeScope(
            employee.getId(),
            employee.getCompanyId(),
            employee.getOnboardingHrId(),
            employee.getEmployeeCode());
    if (!authz.canAccessEmployee(actor, scope)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"); // 404 hides existence
    }
    if (employee.getOnboardingType() != OnboardingType.EXISTING_EMPLOYEE) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Only an existing employee's record is entered by HR");
    }
    if (write
        && employee.getStatus() != EmployeeStatus.INVITED
        && employee.getStatus() != EmployeeStatus.IN_PROGRESS) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This record is already approved");
    }
    return employee;
  }

  /** The employee's team = their onboarding-HR's team — the same resolution the review approve uses. */
  private Team resolveTeam(Employee employee) {
    return teams
        .findByCompanyIdAndHrUserId(employee.getCompanyId(), employee.getOnboardingHrId())
        .orElseThrow(
            () ->
                new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "The onboarding HR is not assigned to a team"));
  }

  private Document loadDocument(String employeeId, String documentId) {
    return documents
        .findByIdAndEmployeeId(documentId, employeeId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
  }

  private Integer normalizeGroupIndex(DocumentType type, Integer groupIndex) {
    if (GROUPED.contains(type)) {
      if (groupIndex == null || groupIndex < 1 || groupIndex > 4) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "This document needs an employment group (1..4)");
      }
      return groupIndex;
    }
    return null;
  }

  private void markInProgress(Employee employee) {
    if (employee.getStatus() == EmployeeStatus.INVITED) {
      employee.setStatus(EmployeeStatus.IN_PROGRESS);
      employees.save(employee);
    }
  }

  private void audit(
      IhrmsPrincipal.User actor,
      Employee employee,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata,
      String ip) {
    audit.record(AuditActor.from(actor), action, targetType, targetId, metadata, ip);
  }
}
