package com.ihrms.offboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
import com.ihrms.domain.model.DocumentRequest;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.RequestDocument;
import com.ihrms.domain.repository.DocumentRequestRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.domain.repository.RequestDocumentRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LetterView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LettersView;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import com.ihrms.storage.StorageService;
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
 * Offboarding letters (§3.6 stage 3) — the Relieving + Experience letters. These REUSE the HR/Accounts
 * document-requests machinery ({@link DocumentRequest} + the fulfil handshake), but route to the offboarding
 * case's HR (not the accountant), gated on every sent document being VERIFIED. HR fulfils via the record
 * panel, delegating to the existing {@code requestDocumentUpload}/{@code resolve} handshake.
 */
@Service
public class OffboardingLetterService {

  private static final Logger log = LoggerFactory.getLogger(OffboardingLetterService.class);
  static final Set<RequestType> LETTER_TYPES =
      Set.of(RequestType.RELIEVING_LETTER, RequestType.EXPERIENCE_LETTER);
  private static final Set<RequestStatus> OPEN =
      Set.of(RequestStatus.SUBMITTED, RequestStatus.IN_PROGRESS);
  private static final int DOWNLOAD_TTL_SECONDS = 300;

  private final EmployeeRepository employees;
  private final OffboardingCaseRepository cases;
  private final OffboardingDocumentRepository offboardingDocs;
  private final DocumentRequestRepository requests;
  private final RequestDocumentRepository requestDocuments;
  private final UserRepository users;
  private final AuthorizationService authz;
  private final StorageService storage;
  private final AuditService audit;
  private final PushService push;

  public OffboardingLetterService(
      EmployeeRepository employees,
      OffboardingCaseRepository cases,
      OffboardingDocumentRepository offboardingDocs,
      DocumentRequestRepository requests,
      RequestDocumentRepository requestDocuments,
      UserRepository users,
      AuthorizationService authz,
      StorageService storage,
      AuditService audit,
      PushService push) {
    this.employees = employees;
    this.cases = cases;
    this.offboardingDocs = offboardingDocs;
    this.requests = requests;
    this.requestDocuments = requestDocuments;
    this.users = users;
    this.authz = authz;
    this.storage = storage;
    this.audit = audit;
    this.push = push;
  }

  static String title(RequestType type) {
    return type == RequestType.RELIEVING_LETTER ? "Relieving Letter" : "Experience Letter";
  }

  // --- Employee: request + read ---------------------------------------------

  /** The employee's letters area — whether the gate is open + their letter requests. */
  @Transactional(readOnly = true)
  public LettersView myLetters(IhrmsPrincipal.Employee emp) {
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    OffboardingCase c = approvedCase(employee.getId());
    return new LettersView(gateOpen(c), letterViews(employee.getId()));
  }

  /**
   * The employee requests a letter (§3.6 stage 3) — routed to the case HR, gated on all documents VERIFIED,
   * at most one open per type.
   */
  @Transactional
  public LetterView requestLetter(IhrmsPrincipal.Employee emp, RequestType type, String note, String ip) {
    if (!LETTER_TYPES.contains(type)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a letter type");
    }
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    OffboardingCase c = approvedCase(employee.getId());
    if (c == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "No approved offboarding case");
    }
    if (!gateOpen(c)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Letters can be requested only after all your offboarding documents have been verified");
    }
    if (requests.existsByEmployeeIdAndRequestTypeAndStatusIn(employee.getId(), type, List.copyOf(OPEN))) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "You already have an open request for this letter");
    }
    DocumentRequest r = new DocumentRequest();
    r.setEmployeeId(employee.getId());
    r.setCompanyId(employee.getCompanyId());
    r.setAccountantUserId(c.getInitiatedByUserId()); // route to the case HR (the routee field)
    r.setRequestType(type);
    r.setNote(note);
    r.setStatus(RequestStatus.SUBMITTED);
    requests.save(r);

    audit.record(
        AuditActor.from(emp), "REQUEST_SUBMITTED", "DocumentRequest", r.getId(),
        Map.of("requestType", type.name(), "routedToHrUserId", c.getInitiatedByUserId()), ip);
    // Best-effort push to the case HR (the record panel is the fulfilment surface).
    try {
      push.sendToPrincipal(
          PrincipalRef.forUser(c.getInitiatedByUserId(), employee.getCompanyId()),
          "Letter requested",
          (employee.getFullName() == null ? "An employee" : employee.getFullName())
              + " requested a " + title(type) + ".",
          "/hr/employees/" + employee.getId());
    } catch (RuntimeException e) {
      log.warn("Letter-request HR push skipped: {}", e.getMessage());
    }
    return letterView(r);
  }

  // --- HR record panel ------------------------------------------------------

  /** The letters area for the HR record (record-view viewers) — gate state + the requests. */
  @Transactional(readOnly = true)
  public LettersView forRecord(IhrmsPrincipal.User actor, String employeeId) {
    authz.assertCanAccessEmployee(actor, employeeId);
    OffboardingCase c = approvedCase(employeeId);
    return new LettersView(gateOpen(c), letterViews(employeeId));
  }

  /** Resolve the letter request id for the fulfilment handshake (HR own-scope). 404 if none. */
  @Transactional(readOnly = true)
  public String letterRequestId(IhrmsPrincipal.User actor, String employeeId, RequestType type) {
    loadOwn(actor, employeeId);
    return requests
        .findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(employeeId, type)
        .map(DocumentRequest::getId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Letter request not found"));
  }

  // --- internals ------------------------------------------------------------

  private List<LetterView> letterViews(String employeeId) {
    return requests
        .findByEmployeeIdAndRequestTypeInOrderByCreatedAtDesc(employeeId, LETTER_TYPES)
        .stream()
        .map(this::letterView)
        .toList();
  }

  private LetterView letterView(DocumentRequest r) {
    String downloadUrl = null;
    if (r.getStatus() == RequestStatus.RESOLVED) {
      List<RequestDocument> bound = requestDocuments.findByRequestIdOrderByCreatedAtAsc(r.getId());
      if (!bound.isEmpty()) {
        downloadUrl = storage.presignedGetUrl(bound.get(0).getStorageKey(), DOWNLOAD_TTL_SECONDS);
      }
    }
    return new LetterView(
        r.getRequestType(),
        title(r.getRequestType()),
        r.getStatus(),
        r.getCreatedAt() == null ? null : r.getCreatedAt().toString(),
        r.getResolvedAt() == null ? null : r.getResolvedAt().toString(),
        r.getNote(),
        downloadUrl);
  }

  /** The gate: every SENT offboarding document exists and is VERIFIED. */
  private boolean gateOpen(OffboardingCase c) {
    if (c == null) {
      return false;
    }
    List<com.ihrms.domain.model.OffboardingDocument> docs = offboardingDocs.findByCaseId(c.getId());
    return !docs.isEmpty() && docs.stream().allMatch(d -> d.getStatus() == OffboardingDocStatus.VERIFIED);
  }

  private OffboardingCase approvedCase(String employeeId) {
    return cases
        .findFirstByEmployeeIdAndStatusIn(
            employeeId, List.of(OffboardingStatus.PENDING_APPROVAL, OffboardingStatus.APPROVED))
        .filter(c -> c.getStatus() == OffboardingStatus.APPROVED)
        .orElse(null);
  }

  private Employee loadOwn(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee =
        employees.findById(employeeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    boolean own =
        employee.getCompanyId().equals(actor.companyId())
            && employee.getOnboardingHrId().equals(actor.userId());
    if (!own) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
    }
    return employee;
  }
}
