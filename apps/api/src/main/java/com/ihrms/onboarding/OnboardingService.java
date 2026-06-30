package com.ihrms.onboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.enums.SectionStatus;
import com.ihrms.domain.model.Document;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.ProfileSection;
import com.ihrms.domain.repository.DocumentRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.ProfileSectionRepository;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentUploadRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.DocumentView;
import com.ihrms.onboarding.dto.OnboardingDtos.OnboardingDashboard;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedUpload;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.onboarding.dto.OnboardingDtos.ProfileSectionView;
import com.ihrms.onboarding.dto.OnboardingDtos.SaveSectionRequest;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Employee self-service onboarding (§3.2). Every operation is scoped to the calling employee's
 * own record (employeeId from the principal; document ops re-check ownership); every mutation and
 * every document view is audited. Document bytes are exchanged only via short-lived presigned URLs
 * — the API never proxies bytes nor exposes storage keys.
 */
@Service
public class OnboardingService {

  private static final int UPLOAD_TTL_SECONDS = 300; // presigned PUT
  private static final int VIEW_TTL_SECONDS = 60; // presigned GET (short-lived, audited download)

  /** Statuses where the employee may still edit their record (before submission). */
  private static final Set<EmployeeStatus> EDITABLE =
      EnumSet.of(EmployeeStatus.INVITED, EmployeeStatus.IN_PROGRESS, EmployeeStatus.REJECTED);

  private final EmployeeRepository employees;
  private final ProfileSectionRepository sections;
  private final DocumentRepository documents;
  private final StorageService storage;
  private final AuditService audit;

  public OnboardingService(
      EmployeeRepository employees,
      ProfileSectionRepository sections,
      DocumentRepository documents,
      StorageService storage,
      AuditService audit) {
    this.employees = employees;
    this.sections = sections;
    this.documents = documents;
    this.storage = storage;
    this.audit = audit;
  }

  public OnboardingDashboard dashboard(IhrmsPrincipal.Employee emp) {
    Employee employee = loadEmployee(emp.employeeId());
    return dashboardOf(employee);
  }

  public ProfileSectionView saveSection(
      IhrmsPrincipal.Employee emp, String keyParam, SaveSectionRequest body, String ip) {
    SectionKey key = parseKey(keyParam);
    Map<String, Object> normalized = SectionValidation.validate(key, body.data());

    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    ProfileSection section =
        sections
            .findByEmployeeIdAndKey(emp.employeeId(), key)
            .orElseGet(
                () -> {
                  ProfileSection s = new ProfileSection();
                  s.setEmployeeId(emp.employeeId());
                  s.setKey(key);
                  return s;
                });
    section.setData(normalized);
    section.setStatus(SectionStatus.DRAFT);
    sections.save(section);
    markInProgress(employee);

    audit(emp, "SECTION_SAVED", "ProfileSection", section.getId(),
        Map.<String, Object>of("key", key.name()), ip);
    return sectionView(section);
  }

  public PresignedUpload requestUpload(
      IhrmsPrincipal.Employee emp, DocumentUploadRequest body, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    assertEditable(employee);

    String storageKey =
        storage.buildKey(emp.companyId(), emp.employeeId(), body.sectionKey().name(), body.fileName());
    Document doc = new Document();
    doc.setEmployeeId(emp.employeeId());
    doc.setSectionKey(body.sectionKey());
    doc.setDocType(body.docType());
    doc.setFileName(body.fileName());
    doc.setStorageKey(storageKey);
    doc.setMimeType(body.mimeType());
    doc.setStatus(DocumentStatus.PENDING);
    documents.save(doc);

    String uploadUrl = storage.presignedPutUrl(storageKey, body.mimeType(), UPLOAD_TTL_SECONDS);
    markInProgress(employee);

    audit(emp, "DOCUMENT_UPLOAD_REQUESTED", "Document", doc.getId(),
        Map.<String, Object>of(
            "sectionKey", body.sectionKey().name(),
            "docType", body.docType().name(),
            "fileName", body.fileName()),
        ip);

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
        Map.<String, Object>of("docType", doc.getDocType().name(), "sha256", sha256, "sizeBytes", bytes.length),
        ip);
    return documentView(doc);
  }

  /** Short-lived presigned GET for viewing/downloading — a sensitive read, audited. */
  public PresignedView documentViewUrl(IhrmsPrincipal.Employee emp, String documentId, String ip) {
    Document doc = loadOwnDocument(emp.employeeId(), documentId);
    String url = storage.presignedGetUrl(doc.getStorageKey(), VIEW_TTL_SECONDS);
    audit(emp, "DOCUMENT_VIEWED", "Document", documentId,
        Map.<String, Object>of("docType", doc.getDocType().name()), ip);
    return new PresignedView(url, VIEW_TTL_SECONDS);
  }

  @Transactional
  public OnboardingDashboard submit(IhrmsPrincipal.Employee emp, String ip) {
    Employee employee = loadEmployee(emp.employeeId());
    if (!EDITABLE.contains(employee.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Cannot submit from status " + employee.getStatus());
    }

    List<ProfileSection> mySections = sections.findByEmployeeId(emp.employeeId());
    List<Document> myDocuments = documents.findByEmployeeId(emp.employeeId());
    if (!Submissions.isComplete(mySections, myDocuments)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Complete all required sections and documents before submitting");
    }

    mySections.forEach(s -> s.setStatus(SectionStatus.SUBMITTED));
    sections.saveAll(mySections);
    employee.setStatus(EmployeeStatus.SUBMITTED);
    employees.save(employee);

    audit(emp, "EMPLOYEE_SUBMITTED", "Employee", emp.employeeId(), null, ip);
    return dashboardOf(employee);
  }

  // --- internals ------------------------------------------------------------

  private OnboardingDashboard dashboardOf(Employee employee) {
    List<ProfileSectionView> sectionViews =
        sections.findByEmployeeIdOrderByKeyAsc(employee.getId()).stream()
            .map(OnboardingService::sectionView)
            .toList();
    List<DocumentView> documentViews =
        documents.findByEmployeeIdOrderByUploadedAtDesc(employee.getId()).stream()
            .map(OnboardingService::documentView)
            .toList();
    return new OnboardingDashboard(
        employee.getEmployeeCode(),
        employee.getEmail(),
        employee.getStatus(),
        sectionViews,
        documentViews);
  }

  private static ProfileSectionView sectionView(ProfileSection s) {
    return new ProfileSectionView(
        s.getKey(),
        s.getData() == null ? Map.of() : s.getData(),
        s.getStatus(),
        s.getUpdatedAt().toString());
  }

  private static DocumentView documentView(Document d) {
    return new DocumentView(
        d.getId(),
        d.getSectionKey(),
        d.getDocType(),
        d.getFileName(),
        d.getMimeType(),
        d.getSha256(),
        d.getStatus(),
        d.getUploadedAt().toString());
  }

  private SectionKey parseKey(String keyParam) {
    try {
      return SectionKey.valueOf(keyParam);
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown section \"" + keyParam + "\"");
    }
  }

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
