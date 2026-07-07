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
import com.ihrms.domain.model.GeneratedDocument;
import com.ihrms.domain.model.Signature;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.Form3PrevEmploymentRepository;
import com.ihrms.domain.repository.GeneratedDocumentRepository;
import com.ihrms.domain.repository.SignatureRepository;
import com.ihrms.onboarding.dto.OnboardingDtos;
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
    assertEditable(employee);

    Form1Personal f1 =
        form1s
            .findByEmployeeId(emp.employeeId())
            .orElseGet(
                () -> {
                  Form1Personal n = new Form1Personal();
                  n.setEmployeeId(emp.employeeId());
                  return n;
                });
    f1.setData(FormMappers.toForm1Data(body));
    f1.setOfferedCtc(body.offeredCtc());
    f1.setStatus(SectionStatus.DRAFT);
    form1s.save(f1);
    markInProgress(employee);

    audit(emp, "FORM1_SAVED", "Form1Personal", f1.getId(), null, ip);
    return FormMappers.form1View(f1, FormMappers.Mode.PLAIN);
  }

  // --- Form 2 ---------------------------------------------------------------

  @Transactional
  public Form2View saveForm2(IhrmsPrincipal.Employee emp, Form2Request body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    Form2Info f2 =
        form2s
            .findByEmployeeId(emp.employeeId())
            .orElseGet(
                () -> {
                  Form2Info n = new Form2Info();
                  n.setEmployeeId(emp.employeeId());
                  return n;
                });
    f2.setData(FormMappers.toForm2Data(body));
    f2.setPanNumber(body.panNumber());
    f2.setAxisAccountNumber(body.axisAccountNumber());
    f2.setStatus(SectionStatus.DRAFT);
    form2s.save(f2);
    markInProgress(employee);

    audit(emp, "FORM2_SAVED", "Form2Info", f2.getId(), null, ip);
    return FormMappers.form2View(f2, employee.getEmployeeCode(), FormMappers.Mode.PLAIN);
  }

  // --- Form 3 ---------------------------------------------------------------

  @Transactional
  public List<Form3EntryView> saveForm3(IhrmsPrincipal.Employee emp, Form3Request body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    // Replace the whole repeatable list (simplest correct upsert for an ordered collection).
    form3s.deleteAll(form3s.findByEmployeeIdOrderByOrderIndexAsc(emp.employeeId()));
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
    assertEditable(loadEmployee(emp.employeeId()));

    byte[] bytes = storage.getObjectBytes(doc.getStorageKey());
    String sha256 = Hashing.sha256Hex(bytes);
    doc.setSha256(sha256);
    doc.setStatus(DocumentStatus.UPLOADED);
    documents.save(doc);

    audit(emp, "DOCUMENT_UPLOADED", "Document", documentId,
        Map.of("docType", doc.getDocType().name(), "sha256", sha256, "sizeBytes", bytes.length), ip);
    return documentView(doc);
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
    Form1View form1 =
        form1s
            .findByEmployeeId(employee.getId())
            .map(f -> FormMappers.form1View(f, FormMappers.Mode.PLAIN))
            .orElse(null);
    Form2View form2 =
        form2s
            .findByEmployeeId(employee.getId())
            .map(f -> FormMappers.form2View(f, employee.getEmployeeCode(), FormMappers.Mode.PLAIN))
            .orElse(null);
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
