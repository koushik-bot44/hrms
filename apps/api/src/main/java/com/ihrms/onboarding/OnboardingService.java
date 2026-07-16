package com.ihrms.onboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Form3PrevEmployment;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.Signature;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.SignatureRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.onboarding.dto.OnboardingDtos;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentReviseRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentUploadRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3Request;
import com.ihrms.onboarding.dto.OnboardingDtos.GeneratedDocumentView;
import com.ihrms.onboarding.dto.OnboardingDtos.OnboardingDashboard;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedUpload;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.onboarding.dto.OnboardingDtos.SignatureRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.SignatureView;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Employee self-service onboarding (§3.2) — the four-form stepper. Every operation is scoped to the
 * calling employee's own record; sensitive fields are encrypted at rest and returned in full only on
 * the employee's own dashboard. On submit the record locks and the five PDFs are generated (one per
 * form + a merged complete application), regenerated on any edit-and-resubmit.
 */
@Service
public class OnboardingService {

  private static final Logger log = LoggerFactory.getLogger(OnboardingService.class);

  private static final int UPLOAD_TTL_SECONDS = 300; // presigned PUT
  private static final int VIEW_TTL_SECONDS = 60; // presigned GET (short-lived, audited)

  /** The per-employment Form 4 slots that carry a groupIndex (1..4). */
  private static final Set<DocumentType> GROUPED =
      EnumSet.of(
          DocumentType.OFFER_OR_APPOINTMENT_LETTER,
          DocumentType.HIKE_LETTER,
          DocumentType.RELIEVING_LETTER);

  /** Statuses where the employee may still edit their record (before/after a rejection). */
  private static final Set<EmployeeStatus> EDITABLE =
      EnumSet.of(EmployeeStatus.INVITED, EmployeeStatus.IN_PROGRESS, EmployeeStatus.REJECTED);

  private final EmployeeRepository employees;
  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final Form3PrevEmploymentRepository form3s;
  private final DocumentRepository documents;
  private final SignatureRepository signatures;
  private final GeneratedDocumentRepository generated;
  private final NotificationRepository notifications;
  private final UserRepository users;
  private final StorageService storage;
  private final PdfService pdf;
  private final AuditService audit;

  public OnboardingService(
      EmployeeRepository employees,
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      Form3PrevEmploymentRepository form3s,
      DocumentRepository documents,
      SignatureRepository signatures,
      GeneratedDocumentRepository generated,
      NotificationRepository notifications,
      UserRepository users,
      StorageService storage,
      PdfService pdf,
      AuditService audit) {
    this.employees = employees;
    this.form1s = form1s;
    this.form2s = form2s;
    this.form3s = form3s;
    this.documents = documents;
    this.signatures = signatures;
    this.generated = generated;
    this.notifications = notifications;
    this.users = users;
    this.storage = storage;
    this.pdf = pdf;
    this.audit = audit;
  }

  public OnboardingDashboard dashboard(IhrmsPrincipal.Employee emp) {
    return dashboardOf(loadEmployee(emp.employeeId()));
  }

  // --- Form 1 ---------------------------------------------------------------

  @Transactional
  public Form1View saveForm1(IhrmsPrincipal.Employee emp, Form1Request body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());

    Form1Personal f1 = form1s.findByEmployeeId(emp.employeeId()).orElse(null);
    assertFormEditable(employee, f1 == null ? null : f1.getStatus());
    if (f1 == null) {
      f1 = new Form1Personal();
      f1.setEmployeeId(emp.employeeId());
    }
    f1.setData(FormMappers.toForm1Data(body));
    f1.setOfferedCtc(body.offeredCtc());
    clearRevision(f1);
    f1.setStatus(SectionStatus.DRAFT);
    form1s.save(f1);

    // Form 1 now CAPTURES the fields relocated from Form 2 (§3.2) — write them through to their
    // unchanged form2_info storage (PAN/account stay encrypted columns; alternate/vehicle stay data
    // keys). Only those fields are touched: Form 2's own values, status and revision are untouched.
    Form2Info f2 = form2s.findByEmployeeId(emp.employeeId()).orElse(null);
    if (f2 == null) {
      f2 = new Form2Info();
      f2.setEmployeeId(emp.employeeId());
    }
    FormMappers.applyForm1RelocatedFields(f2, body);
    form2s.save(f2);
    markInProgress(employee);

    audit(emp, "FORM1_SAVED", "Form1Personal", f1.getId(), null, ip);
    return FormMappers.form1View(f1, f2, FormMappers.Mode.PLAIN);
  }

  // --- Form 2 ---------------------------------------------------------------

  @Transactional
  public Form2View saveForm2(IhrmsPrincipal.Employee emp, Form2Request body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());

    Form2Info f2 = form2s.findByEmployeeId(emp.employeeId()).orElse(null);
    assertFormEditable(employee, f2 == null ? null : f2.getStatus());
    if (f2 == null) {
      f2 = new Form2Info();
      f2.setEmployeeId(emp.employeeId());
    }
    // Carry over the keys relocated to Form 1 (§3.2) — a Form 2 re-save must not wipe them; the
    // PAN/account encrypted columns are simply no longer touched here (Form 1 writes them).
    f2.setData(FormMappers.toForm2Data(body, f2.getData()));
    clearRevision(f2);
    f2.setStatus(SectionStatus.DRAFT);
    form2s.save(f2);
    markInProgress(employee);

    audit(emp, "FORM2_SAVED", "Form2Info", f2.getId(), null, ip);
    return FormMappers.form2View(f2, employee.getEmployeeCode());
  }

  // --- Form 3 ---------------------------------------------------------------

  @Transactional
  public List<Form3EntryView> saveForm3(IhrmsPrincipal.Employee emp, Form3Request body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());

    // Replace the whole repeatable list (simplest correct upsert for an ordered collection). The
    // whole form shares one status, so gate on the first row's state (all rows move together).
    List<Form3PrevEmployment> current = form3s.findByEmployeeIdOrderByOrderIndexAsc(emp.employeeId());
    assertFormEditable(employee, current.isEmpty() ? null : current.get(0).getStatus());
    form3s.deleteAll(current);
    int index = 0;
    if (body.entries() != null) {
      for (var entry : body.entries()) {
        Form3PrevEmployment row = new Form3PrevEmployment();
        row.setEmployeeId(emp.employeeId());
        row.setStatus(SectionStatus.DRAFT);
        FormMappers.applyForm3(row, entry, index++);
        form3s.save(row);
      }
    }
    markInProgress(employee);
    audit(emp, "FORM3_SAVED", "Employee", emp.employeeId(), Map.of("entries", index), ip);
    return form3Views(emp.employeeId(), FormMappers.Mode.PLAIN);
  }

  // --- Form 4: documents ----------------------------------------------------

  public PresignedUpload requestUpload(
      IhrmsPrincipal.Employee emp, DocumentUploadRequest body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    Integer groupIndex = normalizeGroupIndex(body.docType(), body.groupIndex());
    int existing =
        documents.countByEmployeeIdAndDocTypeAndGroupIndex(
            emp.employeeId(), body.docType(), groupIndex);
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
        storage.buildKey(emp.companyId(), emp.employeeId(), "form4", body.fileName());
    Document doc = new Document();
    doc.setEmployeeId(emp.employeeId());
    doc.setDocType(body.docType());
    doc.setGroupIndex(groupIndex);
    doc.setFileName(body.fileName());
    doc.setStorageKey(storageKey);
    doc.setMimeType(body.mimeType());
    doc.setStatus(DocumentStatus.PENDING);
    documents.save(doc);

    String uploadUrl = storage.presignedPutUrl(storageKey, body.mimeType(), UPLOAD_TTL_SECONDS);
    markInProgress(employee);

    audit(emp, "DOCUMENT_UPLOAD_REQUESTED", "Document", doc.getId(),
        Map.of("docType", body.docType().name(), "fileName", body.fileName()), ip);
    return new PresignedUpload(
        doc.getId(), uploadUrl, "PUT", Map.of("Content-Type", body.mimeType()), UPLOAD_TTL_SECONDS);
  }

  public DocumentView confirmUpload(IhrmsPrincipal.Employee emp, String documentId, String ip) {
    Document doc = loadOwnDocument(emp.employeeId(), documentId);
    // Finalises both a fresh upload (PENDING) and a revision re-upload (REVISION_REQUESTED).
    assertCanUploadDocuments(loadEmployee(emp.employeeId()));

    byte[] bytes = storage.getObjectBytes(doc.getStorageKey());
    String sha256 = Hashing.sha256Hex(bytes);
    doc.setSha256(sha256);
    doc.setStatus(DocumentStatus.UPLOADED);
    clearRevision(doc); // a re-uploaded document is no longer flagged
    documents.save(doc);

    audit(emp, "DOCUMENT_UPLOADED", "Document", documentId,
        Map.of("docType", doc.getDocType().name(), "sha256", sha256, "sizeBytes", bytes.length), ip);
    return documentView(doc);
  }

  /**
   * Re-upload a document that HR sent back for revision (§3.3): a fresh presigned PUT keyed to the
   * SAME document, replacing its file in place. Allowed only for a {@code REVISION_REQUESTED} document
   * while the employee is under revision; {@link #confirmUpload} then flips it back to {@code UPLOADED}.
   */
  public PresignedUpload reviseDocument(
      IhrmsPrincipal.Employee emp, String documentId, DocumentReviseRequest body, String ip) {
    Document doc = loadOwnDocument(emp.employeeId(), documentId);
    Employee employee = loadEmployee(emp.employeeId());
    if (employee.getStatus() != EmployeeStatus.REVISION_REQUESTED
        || doc.getStatus() != DocumentStatus.REVISION_REQUESTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This document is not open for revision");
    }

    String oldKey = doc.getStorageKey();
    String storageKey = storage.buildKey(emp.companyId(), emp.employeeId(), "form4", body.fileName());
    doc.setFileName(body.fileName());
    doc.setMimeType(body.mimeType());
    doc.setStorageKey(storageKey);
    // status stays REVISION_REQUESTED until confirm, so a re-do stays possible if the PUT fails.
    documents.save(doc);
    if (!oldKey.equals(storageKey)) {
      try {
        storage.delete(oldKey);
      } catch (RuntimeException e) {
        log.warn("Could not delete replaced bytes for document {}: {}", documentId, e.getMessage());
      }
    }

    String uploadUrl = storage.presignedPutUrl(storageKey, body.mimeType(), UPLOAD_TTL_SECONDS);
    audit(emp, "DOCUMENT_REVISION_UPLOAD_REQUESTED", "Document", doc.getId(),
        Map.of("docType", doc.getDocType().name(), "fileName", body.fileName()), ip);
    return new PresignedUpload(
        doc.getId(), uploadUrl, "PUT", Map.of("Content-Type", body.mimeType()), UPLOAD_TTL_SECONDS);
  }

  public PresignedView documentViewUrl(IhrmsPrincipal.Employee emp, String documentId, String ip) {
    Document doc = loadOwnDocument(emp.employeeId(), documentId);
    String url = storage.presignedGetUrl(doc.getStorageKey(), VIEW_TTL_SECONDS);
    audit(emp, "DOCUMENT_VIEWED", "Document", documentId,
        Map.of("docType", doc.getDocType().name()), ip);
    return new PresignedView(url, VIEW_TTL_SECONDS);
  }

  public OnboardingDashboard deleteDocument(
      IhrmsPrincipal.Employee emp, String documentId, String ip) {
    Document doc = loadOwnDocument(emp.employeeId(), documentId);
    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    documents.delete(doc);
    try {
      storage.delete(doc.getStorageKey());
    } catch (RuntimeException e) {
      log.warn("Could not delete stored bytes for document {}: {}", documentId, e.getMessage());
    }
    audit(emp, "DOCUMENT_DELETED", "Document", documentId,
        Map.of("docType", doc.getDocType().name(), "fileName", doc.getFileName()), ip);
    return dashboardOf(employee);
  }

  // --- Signature ------------------------------------------------------------

  @Transactional
  public SignatureView saveSignature(IhrmsPrincipal.Employee emp, SignatureRequest body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    DecodedImage image = decodeDataUrl(body.imageDataUrl());
    String key = storage.buildKey(emp.companyId(), emp.employeeId(), "signature", "signature.png");

    Signature signature = signatures.findByEmployeeId(emp.employeeId()).orElse(null);
    if (signature != null && !signature.getStorageKey().equals(key)) {
      try {
        storage.delete(signature.getStorageKey());
      } catch (RuntimeException e) {
        log.warn("Could not delete old signature for {}: {}", emp.employeeId(), e.getMessage());
      }
    }
    if (signature == null) {
      signature = new Signature();
      signature.setEmployeeId(emp.employeeId());
    }
    storage.putObject(key, image.bytes(), image.contentType());
    signature.setStorageKey(key);
    signature.setType(body.type());
    signature.setSignedAt(java.time.Instant.now()); // set explicitly so the view can read it pre-flush
    signatures.save(signature);
    markInProgress(employee);

    audit(emp, "SIGNATURE_CAPTURED", "Signature", signature.getId(),
        Map.of("type", body.type()), ip);
    return new SignatureView(signature.getType(), signature.getSignedAt().toString());
  }

  // --- Submit ---------------------------------------------------------------

  @Transactional
  public OnboardingDashboard submit(IhrmsPrincipal.Employee emp, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    if (!EDITABLE.contains(employee.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cannot submit from status " + employee.getStatus());
    }

    Form1Personal f1 = form1s.findByEmployeeId(emp.employeeId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(emp.employeeId()).orElse(null);
    List<Document> docs = documents.findByEmployeeId(emp.employeeId());
    Signature signature = signatures.findByEmployeeId(emp.employeeId()).orElse(null);

    List<String> missing = OnboardingCompleteness.missing(f1, f2, docs, signature);
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Before submitting: " + String.join("; ", missing));
    }

    // Lock every form/item into SUBMITTED, flip the employee, then generate the PDFs.
    f1.setStatus(SectionStatus.SUBMITTED);
    form1s.save(f1);
    f2.setStatus(SectionStatus.SUBMITTED);
    form2s.save(f2);
    List<Form3PrevEmployment> f3 = form3s.findByEmployeeIdOrderByOrderIndexAsc(emp.employeeId());
    f3.forEach(r -> r.setStatus(SectionStatus.SUBMITTED));
    form3s.saveAll(f3);
    employee.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(employee);

    audit(emp, "EMPLOYEE_SUBMITTED", "Employee", emp.employeeId(), null, ip);
    return dashboardOf(employee);
  }

  // --- Re-submit after a revision (§3.3) ------------------------------------

  /**
   * Re-submit after HR sent one or more items back for revision. Requires the employee to be under
   * revision with <strong>every</strong> flagged item already updated (no form/document still
   * {@code REVISION_REQUESTED}); re-runs the completeness gate; returns each revised item to
   * awaiting-HR ({@code SUBMITTED} / {@code UPLOADED} — which it already is), flips the employee back
   * to {@code SUBMITTED}, and notifies the onboarding HR. PDFs regenerate post-commit (controller).
   */
  @Transactional
  public OnboardingDashboard resubmit(IhrmsPrincipal.Employee emp, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    if (employee.getStatus() != EmployeeStatus.REVISION_REQUESTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "No revision is in progress on your record");
    }

    Form1Personal f1 = form1s.findByEmployeeId(emp.employeeId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(emp.employeeId()).orElse(null);
    List<Form3PrevEmployment> f3 = form3s.findByEmployeeIdOrderByOrderIndexAsc(emp.employeeId());
    List<Document> docs = documents.findByEmployeeId(emp.employeeId());
    Signature signature = signatures.findByEmployeeId(emp.employeeId()).orElse(null);

    boolean stillFlagged =
        (f1 != null && f1.getStatus() == SectionStatus.REVISION_REQUESTED)
            || (f2 != null && f2.getStatus() == SectionStatus.REVISION_REQUESTED)
            || f3.stream().anyMatch(r -> r.getStatus() == SectionStatus.REVISION_REQUESTED)
            || docs.stream().anyMatch(d -> d.getStatus() == DocumentStatus.REVISION_REQUESTED);
    if (stillFlagged) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Update every item HR sent back before re-submitting");
    }

    List<String> missing = OnboardingCompleteness.missing(f1, f2, docs, signature);
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Before re-submitting: " + String.join("; ", missing));
    }

    // Return the revised forms (now DRAFT) to awaiting-HR; already-verified forms stay VERIFIED.
    if (f1 != null && f1.getStatus() == SectionStatus.DRAFT) {
      f1.setStatus(SectionStatus.SUBMITTED);
      form1s.save(f1);
    }
    if (f2 != null && f2.getStatus() == SectionStatus.DRAFT) {
      f2.setStatus(SectionStatus.SUBMITTED);
      form2s.save(f2);
    }
    f3.stream()
        .filter(r -> r.getStatus() == SectionStatus.DRAFT)
        .forEach(r -> r.setStatus(SectionStatus.SUBMITTED));
    form3s.saveAll(f3);

    employee.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(employee);

    // Notify the onboarding HR that the record is back for re-review (§3.3).
    String hrUserId = employee.getOnboardingHrId();
    if (hrUserId != null) {
      Notification notification = new Notification();
      notification.setRecipientUserId(hrUserId);
      notification.setType(NotificationType.EMPLOYEE_SUBMITTED);
      notification.setEmployeeId(employee.getId());
      notifications.save(notification);
      users
          .findById(hrUserId)
          .map(User::getEmail)
          .ifPresent(
              hrEmail ->
                  log.warn(
                      "[DEV RESUBMIT] {} re-submitted revised onboarding for {} ({}) — ready for re-review",
                      hrEmail,
                      employee.getFullName(),
                      employee.getEmail()));
    }

    audit(emp, "EMPLOYEE_RESUBMITTED", "Employee", emp.employeeId(), null, ip);
    return dashboardOf(employee);
  }

  /**
   * (Re)generate the employee's PDFs — called by the controller AFTER {@link #submit} commits
   * (post-commit, best-effort), so a storage/rendering failure is logged but never fails or rolls
   * back the submission.
   */
  public void regeneratePdfsQuietly(IhrmsPrincipal.Employee emp) {
    try {
      employees.findById(emp.employeeId()).ifPresent(pdf::generateForEmployee);
    } catch (Exception e) {
      log.error("Onboarding PDF generation failed for employee {} (non-fatal)", emp.employeeId(), e);
    }
  }

  public PresignedView generatedViewUrl(IhrmsPrincipal.Employee emp, String generatedId, String ip) {
    GeneratedDocument doc =
        generated
            .findByIdAndEmployeeId(generatedId, emp.employeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
    String url = storage.presignedGetUrl(doc.getStorageKey(), VIEW_TTL_SECONDS);
    audit(emp, "GENERATED_DOCUMENT_VIEWED", "GeneratedDocument", generatedId,
        Map.of("kind", doc.getKind().name()), ip);
    return new PresignedView(url, VIEW_TTL_SECONDS);
  }

  // --- internals ------------------------------------------------------------

  private OnboardingDashboard dashboardOf(Employee employee) {
    // Form 1's view surfaces the relocated fields from their form2_info storage (§3.2).
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    Form1View form1 =
        form1s
            .findByEmployeeId(employee.getId())
            .map(f -> FormMappers.form1View(f, f2, FormMappers.Mode.PLAIN))
            .orElse(null);
    Form2View form2 = f2 == null ? null : FormMappers.form2View(f2, employee.getEmployeeCode());
    List<Form3EntryView> form3 = form3Views(employee.getId(), FormMappers.Mode.PLAIN);
    List<DocumentView> docs =
        documents.findByEmployeeIdOrderByUploadedAtDesc(employee.getId()).stream()
            .map(OnboardingService::documentView)
            .toList();
    SignatureView signature =
        signatures
            .findByEmployeeId(employee.getId())
            .map(s -> new SignatureView(s.getType(), s.getSignedAt().toString()))
            .orElse(null);
    List<GeneratedDocumentView> gen =
        generated.findByEmployeeIdOrderByKindAsc(employee.getId()).stream()
            .map(OnboardingService::generatedView)
            .toList();
    return new OnboardingDashboard(
        employee.getEmployeeCode(),
        employee.getEmail(),
        employee.getFullName(),
        employee.getDesignation(),
        employee.getStatus(),
        form1,
        form2,
        form3,
        docs,
        signature,
        gen);
  }

  private List<Form3EntryView> form3Views(String employeeId, FormMappers.Mode mode) {
    return form3s.findByEmployeeIdOrderByOrderIndexAsc(employeeId).stream()
        .map(e -> FormMappers.form3View(e, mode))
        .toList();
  }

  private static DocumentView documentView(Document d) {
    return new DocumentView(
        d.getId(),
        d.getDocType(),
        d.getGroupIndex(),
        d.getFileName(),
        d.getMimeType(),
        d.getSha256(),
        d.getStatus(),
        d.getRevisionNote(),
        d.getUploadedAt().toString());
  }

  private static GeneratedDocumentView generatedView(GeneratedDocument d) {
    return new GeneratedDocumentView(
        d.getId(), d.getKind(), d.getFileName(), d.getSha256(), d.getGeneratedAt().toString());
  }

  private Integer normalizeGroupIndex(DocumentType type, Integer groupIndex) {
    if (GROUPED.contains(type)) {
      if (groupIndex == null || groupIndex < 1 || groupIndex > 4) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "This document needs an employment group (1..4)");
      }
      return groupIndex;
    }
    return null; // single-slot documents never carry a group
  }

  private DecodedImage decodeDataUrl(String dataUrl) {
    try {
      int comma = dataUrl.indexOf(',');
      String meta = dataUrl.substring("data:".length(), comma); // e.g. image/png;base64
      String contentType = meta.split(";")[0];
      byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
      if (bytes.length == 0 || bytes.length > OnboardingDtos.MAX_UPLOAD_BYTES) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature image is empty or too large");
      }
      return new DecodedImage(bytes, contentType);
    } catch (ResponseStatusException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the signature image");
    }
  }

  private record DecodedImage(byte[] bytes, String contentType) {}

  private Employee loadEmployee(String id) {
    return employees
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
  }

  private Document loadOwnDocument(String employeeId, String documentId) {
    return documents
        .findByIdAndEmployeeId(documentId, employeeId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
  }

  private void assertEditable(Employee employee) {
    if (!EDITABLE.contains(employee.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Your record is locked for verification");
    }
  }

  /**
   * A form is editable when the employee is in normal editing ({@link #EDITABLE}), or — during a
   * revision — only when that specific form was flagged and is being worked on (its status is
   * {@code REVISION_REQUESTED} or, once saved, {@code DRAFT}). Every other item stays locked (§3.3).
   */
  private void assertFormEditable(Employee employee, SectionStatus currentFormStatus) {
    if (EDITABLE.contains(employee.getStatus())) {
      return;
    }
    if (employee.getStatus() == EmployeeStatus.REVISION_REQUESTED
        && (currentFormStatus == SectionStatus.REVISION_REQUESTED
            || currentFormStatus == SectionStatus.DRAFT)) {
      return;
    }
    throw new ResponseStatusException(
        HttpStatus.CONFLICT, "This form is locked — only items HR sent back can be edited");
  }

  /** Confirming an upload is allowed for a fresh upload (EDITABLE) or a revision re-upload. */
  private void assertCanUploadDocuments(Employee employee) {
    if (!EDITABLE.contains(employee.getStatus())
        && employee.getStatus() != EmployeeStatus.REVISION_REQUESTED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Your record is locked for verification");
    }
  }

  private static void clearRevision(Form1Personal f) {
    f.setRevisionNote(null);
    f.setRevisionRequestedAt(null);
  }

  private static void clearRevision(Form2Info f) {
    f.setRevisionNote(null);
    f.setRevisionRequestedAt(null);
  }

  private static void clearRevision(Document d) {
    d.setRevisionNote(null);
    d.setRevisionRequestedAt(null);
  }

  private void markInProgress(Employee employee) {
    if (employee.getStatus() == EmployeeStatus.INVITED) {
      employee.setStatus(EmployeeStatus.IN_PROGRESS);
      employees.save(employee);
    }
  }

  private void audit(
      IhrmsPrincipal.Employee emp,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata,
      String ip) {
    audit.record(AuditActor.from(emp), action, targetType, targetId, metadata, ip);
  }
}
