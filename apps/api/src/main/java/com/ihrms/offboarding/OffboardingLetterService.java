package com.ihrms.offboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.DocumentRequest;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.OffboardingDocument;
import com.ihrms.domain.model.OffboardingLetter;
import com.ihrms.domain.model.RequestDocument;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.DocumentRequestRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.domain.repository.OffboardingLetterRepository;
import com.ihrms.domain.repository.RequestDocumentRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.offboarding.dto.OffboardingDocDtos.FieldKind;
import com.ihrms.offboarding.dto.OffboardingDocDtos.FieldView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.IssueLetterRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LetterGender;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LetterIssuePanel;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LetterIssueSpec;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LetterPreview;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LetterView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.LettersView;
import com.ihrms.onboarding.HtmlPdfRenderer;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * Offboarding letters (§3.6 stage 3) — the Relieving + Experience letters. These are COMPANY-ISSUED: HR
 * generates the PDF from single-source templates (the employee never fills or signs them). The employee may
 * still REQUEST a letter (a {@link DocumentRequest} routed to the case HR, reusing the requests machinery);
 * issuing a letter with an open request RESOLVES it (binding the generated PDF so the requests flow sees it
 * fulfilled), otherwise it issues directly. Both paths are gated on every sent offboarding document being
 * VERIFIED. Rendering + storage reuse the agreements/documents pipeline (single-source fragment + the
 * letterhead-slot template). The upload fulfil handshake remains as an HR fallback on a request.
 */
@Service
public class OffboardingLetterService {

  private static final Logger log = LoggerFactory.getLogger(OffboardingLetterService.class);
  static final Set<RequestType> LETTER_TYPES =
      Set.of(RequestType.RELIEVING_LETTER, RequestType.EXPERIENCE_LETTER);
  /** Display order (Relieving, then Experience) for the panels. */
  private static final List<RequestType> ORDERED_LETTER_TYPES =
      List.of(RequestType.RELIEVING_LETTER, RequestType.EXPERIENCE_LETTER);
  private static final List<RequestStatus> OPEN =
      List.of(RequestStatus.SUBMITTED, RequestStatus.IN_PROGRESS);
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final int DOWNLOAD_TTL_SECONDS = 300;
  private static final String GATE_MSG =
      "Letters can be issued only after all the employee's offboarding documents have been verified";

  private final EmployeeRepository employees;
  private final OffboardingCaseRepository cases;
  private final OffboardingDocumentRepository offboardingDocs;
  private final OffboardingLetterRepository letterRepo;
  private final DocumentRequestRepository requests;
  private final RequestDocumentRepository requestDocuments;
  private final CompanyRepository companies;
  private final UserRepository users;
  private final AuthorizationService authz;
  private final StorageService storage;
  private final HtmlPdfRenderer html;
  private final OffboardingLetterTemplates templates;
  private final AuditService audit;
  private final MailService mail;
  private final PushService push;

  public OffboardingLetterService(
      EmployeeRepository employees,
      OffboardingCaseRepository cases,
      OffboardingDocumentRepository offboardingDocs,
      OffboardingLetterRepository letterRepo,
      DocumentRequestRepository requests,
      RequestDocumentRepository requestDocuments,
      CompanyRepository companies,
      UserRepository users,
      AuthorizationService authz,
      StorageService storage,
      HtmlPdfRenderer html,
      OffboardingLetterTemplates templates,
      AuditService audit,
      MailService mail,
      PushService push) {
    this.employees = employees;
    this.cases = cases;
    this.offboardingDocs = offboardingDocs;
    this.letterRepo = letterRepo;
    this.requests = requests;
    this.requestDocuments = requestDocuments;
    this.companies = companies;
    this.users = users;
    this.authz = authz;
    this.storage = storage;
    this.html = html;
    this.templates = templates;
    this.audit = audit;
    this.mail = mail;
    this.push = push;
  }

  static String title(RequestType type) {
    return type == RequestType.RELIEVING_LETTER ? "Relieving Letter" : "Experience Letter";
  }

  // --- Employee: request + read ---------------------------------------------

  /** The employee's letters area — whether the gate is open + their letters (requested or issued). */
  @Transactional(readOnly = true)
  public LettersView myLetters(IhrmsPrincipal.Employee emp) {
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    OffboardingCase c = approvedCase(employee.getId());
    return new LettersView(gateOpen(c), letterViews(employee.getId(), c));
  }

  /**
   * The employee requests a letter (§3.6 stage 3) — routed to the case HR, gated on all documents VERIFIED,
   * at most one open per type. HR may also issue with no request (see {@link #issue}).
   */
  @Transactional
  public LetterView requestLetter(IhrmsPrincipal.Employee emp, RequestType type, String note, String ip) {
    requireLetterType(type);
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    OffboardingCase c = approvedCase(employee.getId());
    if (c == null) {
      throw conflict("No approved offboarding case");
    }
    if (!gateOpen(c)) {
      throw conflict(
          "Letters can be requested only after all your offboarding documents have been verified");
    }
    if (requests.existsByEmployeeIdAndRequestTypeAndStatusIn(employee.getId(), type, OPEN)) {
      throw conflict("You already have an open request for this letter");
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
    return letterViewForType(employee.getId(), c, type);
  }

  // --- HR record panel: issue -----------------------------------------------

  /** The letters section for the HR record (record-view viewers): the gate state + the two issue specs. */
  @Transactional(readOnly = true)
  public LetterIssuePanel issuePanel(IhrmsPrincipal.User actor, String employeeId) {
    authz.assertCanAccessEmployee(actor, employeeId);
    Employee employee = employees.findById(employeeId).orElseThrow(() -> notFound("Employee not found"));
    return buildPanel(employee, caseForLetters(employeeId));
  }

  /** Render the substituted letter body for the HR preview dialog (no gate — a dry run). HR own-scope. */
  @Transactional(readOnly = true)
  public LetterPreview preview(IhrmsPrincipal.User actor, String employeeId, RequestType type, IssueLetterRequest body) {
    Employee employee = loadOwn(actor, employeeId);
    requireLetterType(type);
    User hr = users.findById(actor.userId()).orElseThrow();
    Map<String, String> hrValues = body == null || body.hrValues() == null ? Map.of() : body.hrValues();
    Map<String, String> tokens = buildTokens(type, employee, hr, hrValues, body == null ? null : body.gender());
    return new LetterPreview(templates.render(type, tokens));
  }

  /**
   * HR issues a letter: generate the PDF (gated on all documents VERIFIED), persist the issued row (re-issue
   * overwrites the stable key), and — if an open request exists — resolve it by binding the generated PDF.
   */
  @Transactional
  public LetterIssuePanel issue(
      IhrmsPrincipal.User actor, String employeeId, RequestType type, IssueLetterRequest body, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    requireLetterType(type);
    OffboardingCase c = approvedCase(employeeId);
    if (c == null) {
      throw conflict("This employee has no approved offboarding case");
    }
    if (!gateOpen(c)) {
      throw conflict(GATE_MSG);
    }
    Map<String, String> hrValues = body == null || body.hrValues() == null ? Map.of() : body.hrValues();
    LetterGender gender = body == null ? null : body.gender();
    validateIssue(type, employee, c, hrValues, gender);

    User hr = users.findById(actor.userId()).orElseThrow();
    Map<String, String> tokens = buildTokens(type, employee, hr, hrValues, gender);
    byte[] pdf = html.render("agreement", pdfModel(templates.render(type, tokens)));
    String key =
        storage.buildKey(
            employee.getCompanyId(), employee.getId(), "offboarding", type.name().toLowerCase() + ".pdf");
    storage.putObject(key, pdf, "application/pdf");

    OffboardingLetter letter =
        letterRepo.findByCaseIdAndType(c.getId(), type).orElseGet(OffboardingLetter::new);
    letter.setCaseId(c.getId());
    letter.setType(type);
    letter.setStorageKey(key);
    letter.setHrValues(storedValues(hrValues, gender));
    letter.setIssuedByUserId(actor.userId());
    letter.setIssuedAt(Instant.now());
    letterRepo.save(letter);

    resolveMatchingRequest(employee, type, key, pdf, actor, ip);

    audit.record(
        AuditActor.from(actor),
        "LETTER_ISSUED",
        "OffboardingLetter",
        letter.getId(),
        Map.of("type", type.name(), "sha256", Hashing.sha256Hex(pdf), "storageKey", key),
        ip);
    return buildPanel(employee, c);
  }

  /** Best-effort employee notice after a letter is issued (mail + push). Never throws. */
  public void notifyAfterIssue(String employeeId, RequestType type) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null) {
        return;
      }
      mail.sendOffboardingLetterIssued(employee.getEmail(), employee.getFullName(), title(type));
      push.sendToPrincipal(
          PrincipalRef.forEmployee(employee.getId(), employee.getCompanyId()),
          "Letter ready",
          "Your " + title(type) + " is ready to download.",
          "/workspace/offboarding");
    } catch (RuntimeException e) {
      log.warn("Post-issue letter notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- HR record panel: the upload fulfil FALLBACK --------------------------

  /** Resolve the letter request id for the upload fulfilment handshake (HR own-scope). 404 if none. */
  @Transactional(readOnly = true)
  public String letterRequestId(IhrmsPrincipal.User actor, String employeeId, RequestType type) {
    loadOwn(actor, employeeId);
    return requests
        .findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(employeeId, type)
        .map(DocumentRequest::getId)
        .orElseThrow(() -> notFound("Letter request not found"));
  }

  // --- internals: issuing ---------------------------------------------------

  /** If a matching request exists: resolve an OPEN one (bind the PDF); refresh a RESOLVED one on re-issue. */
  private void resolveMatchingRequest(
      Employee employee, RequestType type, String key, byte[] pdf, IhrmsPrincipal.User actor, String ip) {
    DocumentRequest r =
        requests.findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(employee.getId(), type).orElse(null);
    if (r == null) {
      return;
    }
    String sha = Hashing.sha256Hex(pdf);
    if (r.isOpen()) {
      RequestDocument doc = new RequestDocument();
      doc.setRequestId(r.getId());
      doc.setFileName(title(type) + ".pdf");
      doc.setContentType("application/pdf");
      doc.setSizeBytes(pdf.length);
      doc.setStorageKey(key);
      doc.setUploadedByUserId(actor.userId());
      doc.setSha256(sha);
      requestDocuments.save(doc);

      r.setStatus(RequestStatus.RESOLVED);
      r.setResolvedAt(Instant.now());
      r.setResolveNote("Issued by HR");
      requests.save(r);

      audit.record(
          AuditActor.from(actor), "REQUEST_RESOLVED", "DocumentRequest", r.getId(),
          Map.of("employeeId", employee.getId(), "documentCount", 1), ip);
      audit.record(
          AuditActor.from(actor), "REQUEST_DOCUMENT_UPLOADED", "RequestDocument", doc.getId(),
          Map.of("requestId", r.getId(), "fileName", doc.getFileName(), "sha256", sha), ip);
    } else if (r.getStatus() == RequestStatus.RESOLVED) {
      // Re-issue: the bound file lives at the same stable key — refresh its integrity record to match.
      List<RequestDocument> bound = requestDocuments.findByRequestIdOrderByCreatedAtAsc(r.getId());
      boolean changed = false;
      for (RequestDocument d : bound) {
        if (key.equals(d.getStorageKey())) {
          d.setSha256(sha);
          d.setSizeBytes(pdf.length);
          changed = true;
        }
      }
      if (changed) {
        requestDocuments.saveAll(bound);
      }
    }
  }

  private LetterIssuePanel buildPanel(Employee employee, OffboardingCase c) {
    boolean gate = gateOpen(c);
    List<LetterIssueSpec> specs = new ArrayList<>();
    for (RequestType type : ORDERED_LETTER_TYPES) {
      OffboardingLetter issued = c == null ? null : letterRepo.findByCaseIdAndType(c.getId(), type).orElse(null);
      DocumentRequest r =
          requests.findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(employee.getId(), type).orElse(null);
      Map<String, Object> stored = issued == null ? null : issued.getHrValues();
      specs.add(
          new LetterIssueSpec(
              type,
              title(type),
              type == RequestType.EXPERIENCE_LETTER,
              fieldSpec(type, employee, c, stored),
              issued != null,
              issued == null ? null : issued.getIssuedAt().toString(),
              issued == null ? null : storage.presignedGetUrl(issued.getStorageKey(), DOWNLOAD_TTL_SECONDS),
              r == null ? null : r.getStatus(),
              r == null ? null : r.getNote(),
              r == null ? null : iso(r.getCreatedAt()),
              genderFrom(stored)));
    }
    return new LetterIssuePanel(gate, specs);
  }

  /** The HR-at-issue field spec for a letter, prefilled from the record (and stored values on re-issue). */
  private List<FieldView> fieldSpec(
      RequestType type, Employee employee, OffboardingCase c, Map<String, Object> stored) {
    String today = LocalDate.now().format(DMY);
    String joining = employee.getDateOfJoining() == null ? "" : employee.getDateOfJoining().format(DMY);
    String lastDay = c == null || c.getLastWorkingDay() == null ? "" : c.getLastWorkingDay().format(DMY);
    String designation = nn(employee.getDesignation());
    List<FieldView> base =
        switch (type) {
          case RELIEVING_LETTER ->
              List.of(
                  new FieldView("DATE", "Letter date", FieldKind.TEXT, today, true),
                  new FieldView("RESIGNATION_DATE", "Resignation letter date", FieldKind.TEXT, "", true),
                  new FieldView("RELIEVING_DATE", "Relieving date", FieldKind.TEXT, lastDay, true),
                  new FieldView("TENURE_FROM", "Tenure from", FieldKind.TEXT, joining, true),
                  new FieldView("TENURE_TO", "Tenure to", FieldKind.TEXT, lastDay, true),
                  new FieldView("DESIGNATION", "Designation", FieldKind.TEXT, designation, true));
          case EXPERIENCE_LETTER ->
              List.of(
                  new FieldView("DATE", "Letter date", FieldKind.TEXT, today, true),
                  new FieldView("DESIGNATION", "Designation", FieldKind.TEXT, designation, true),
                  new FieldView("TENURE_FROM", "Tenure from", FieldKind.TEXT, joining, true),
                  new FieldView("TENURE_TO", "Tenure to", FieldKind.TEXT, lastDay, true));
          default -> List.of();
        };
    if (stored == null || stored.isEmpty()) {
      return base;
    }
    List<FieldView> out = new ArrayList<>();
    for (FieldView f : base) {
      Object sv = stored.get(f.key());
      out.add(
          sv == null
              ? f
              : new FieldView(f.key(), f.label(), f.kind(), String.valueOf(sv), f.required()));
    }
    return out;
  }

  private void validateIssue(
      RequestType type, Employee employee, OffboardingCase c, Map<String, String> hrValues, LetterGender gender) {
    for (FieldView f : fieldSpec(type, employee, c, null)) {
      if (f.required() && blank(hrValues.get(f.key()))) {
        throw badRequest("Missing required value: " + f.label());
      }
    }
    if (type == RequestType.EXPERIENCE_LETTER && gender == null) {
      throw badRequest("Choose he/she for the experience letter");
    }
  }

  /** Company/HR/record + the HR field values + (Experience) the cased pronoun + title tokens. */
  private Map<String, String> buildTokens(
      RequestType type, Employee employee, User issuingHr, Map<String, String> hrValues, LetterGender gender) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("COMPANY_NAME", companyName(employee));
    t.put("HR_NAME", nn(issuingHr.getName()));
    t.put("EMPLOYEE_NAME", nn(employee.getFullName()));
    t.put("EMPLOYEE_CODE", nn(employee.getEmployeeCode()));
    hrValues.forEach((k, v) -> t.put(k, v == null ? "" : v));
    if (type == RequestType.EXPERIENCE_LETTER) {
      boolean female = gender == LetterGender.FEMALE;
      t.put("he_she", female ? "she" : "he");
      t.put("He_She", female ? "She" : "He");
      t.put("his_her", female ? "her" : "his");
      t.put("His_Her", female ? "Her" : "His");
      t.put("him_her", female ? "her" : "him");
      t.put("Him_Her", female ? "Her" : "Him");
      t.put("TITLE", female ? "Ms." : "Mr.");
    }
    return t;
  }

  private Map<String, Object> storedValues(Map<String, String> hrValues, LetterGender gender) {
    Map<String, Object> out = new LinkedHashMap<>(hrValues);
    if (gender != null) {
      out.put("GENDER", gender.name());
    }
    return out;
  }

  private LetterGender genderFrom(Map<String, Object> stored) {
    if (stored == null) {
      return null;
    }
    Object g = stored.get("GENDER");
    if (g == null) {
      return null;
    }
    try {
      return LetterGender.valueOf(String.valueOf(g));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  private Map<String, Object> pdfModel(String bodyHtml) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("bodyHtml", bodyHtml);
    m.put("footerText", "Private & Confidential");
    return m;
  }

  // --- internals: views -----------------------------------------------------

  /** Per-type letter views for the employee — a type is shown only when requested OR issued. */
  private List<LetterView> letterViews(String employeeId, OffboardingCase c) {
    List<LetterView> out = new ArrayList<>();
    for (RequestType type : ORDERED_LETTER_TYPES) {
      LetterView v = letterViewForType(employeeId, c, type);
      if (v.issued() || v.requestStatus() != null) {
        out.add(v);
      }
    }
    return out;
  }

  private LetterView letterViewForType(String employeeId, OffboardingCase c, RequestType type) {
    OffboardingLetter issued = c == null ? null : letterRepo.findByCaseIdAndType(c.getId(), type).orElse(null);
    DocumentRequest r =
        requests.findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(employeeId, type).orElse(null);
    String downloadUrl = null;
    if (issued != null) {
      downloadUrl = storage.presignedGetUrl(issued.getStorageKey(), DOWNLOAD_TTL_SECONDS);
    } else if (r != null && r.getStatus() == RequestStatus.RESOLVED) {
      // Upload fallback: the fulfilled file is bound to the request.
      downloadUrl =
          requestDocuments.findByRequestIdOrderByCreatedAtAsc(r.getId()).stream()
              .filter(RequestDocument::isBound)
              .findFirst()
              .map(d -> storage.presignedGetUrl(d.getStorageKey(), DOWNLOAD_TTL_SECONDS))
              .orElse(null);
    }
    return new LetterView(
        type,
        title(type),
        issued != null,
        r == null ? null : r.getStatus(),
        r == null ? null : iso(r.getCreatedAt()),
        issued == null ? null : issued.getIssuedAt().toString(),
        r == null ? null : r.getNote(),
        downloadUrl);
  }

  // --- internals: gate + scoping --------------------------------------------

  /** The gate: the case is APPROVED and every SENT offboarding document exists and is VERIFIED. */
  private boolean gateOpen(OffboardingCase c) {
    if (c == null || c.getStatus() != OffboardingStatus.APPROVED) {
      return false;
    }
    List<OffboardingDocument> docs = offboardingDocs.findByCaseId(c.getId());
    return !docs.isEmpty() && docs.stream().allMatch(d -> d.getStatus() == OffboardingDocStatus.VERIFIED);
  }

  /** The employee's APPROVED case (issuing / requesting / the gate) — null otherwise. */
  private OffboardingCase approvedCase(String employeeId) {
    return cases
        .findFirstByEmployeeIdAndStatusIn(
            employeeId, List.of(OffboardingStatus.PENDING_APPROVAL, OffboardingStatus.APPROVED))
        .filter(c -> c.getStatus() == OffboardingStatus.APPROVED)
        .orElse(null);
  }

  /**
   * The case whose letters we READ (the HR record panel) — the latest APPROVED or COMPLETED case, so issued
   * letters remain visible after the employee is offboarded (retention).
   */
  private OffboardingCase caseForLetters(String employeeId) {
    OffboardingCase c = cases.findFirstByEmployeeIdOrderByInitiatedAtDesc(employeeId).orElse(null);
    return c != null
            && (c.getStatus() == OffboardingStatus.APPROVED || c.getStatus() == OffboardingStatus.COMPLETED)
        ? c
        : null;
  }

  private Employee loadOwn(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee =
        employees.findById(employeeId).orElseThrow(() -> notFound("Employee not found"));
    boolean own =
        employee.getCompanyId().equals(actor.companyId())
            && employee.getOnboardingHrId().equals(actor.userId());
    if (!own) {
      throw notFound("Employee not found");
    }
    return employee;
  }

  // --- small helpers --------------------------------------------------------

  private void requireLetterType(RequestType type) {
    if (!LETTER_TYPES.contains(type)) {
      throw badRequest("Not a letter type");
    }
  }

  private String companyName(Employee employee) {
    return companies.findById(employee.getCompanyId()).map(Company::getName).orElse("Company");
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
  }

  private static String nn(String s) {
    return s == null ? "" : s;
  }

  private static String iso(Instant i) {
    return i == null ? null : i.toString();
  }

  private static ResponseStatusException notFound(String m) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
  }

  private static ResponseStatusException conflict(String m) {
    return new ResponseStatusException(HttpStatus.CONFLICT, m);
  }

  private static ResponseStatusException badRequest(String m) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
  }
}
