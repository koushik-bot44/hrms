package com.ihrms.offboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.enums.OffboardingDocStatus;
import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.OffboardingCase;
import com.ihrms.domain.model.OffboardingDocument;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.OffboardingCaseRepository;
import com.ihrms.domain.repository.OffboardingDocumentRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.offboarding.dto.OffboardingDocDtos.CompleteDocRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.CompleteDocResult;
import com.ihrms.offboarding.dto.OffboardingDocDtos.DocSummary;
import com.ihrms.offboarding.dto.OffboardingDocDtos.FieldKind;
import com.ihrms.offboarding.dto.OffboardingDocDtos.FieldView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.MyDocSummary;
import com.ihrms.offboarding.dto.OffboardingDocDtos.MyDocView;
import com.ihrms.offboarding.dto.OffboardingDocDtos.RecordDocuments;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendDocSelection;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendDocumentsRequest;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendDocumentsResult;
import com.ihrms.offboarding.dto.OffboardingDocDtos.SendableDoc;
import com.ihrms.onboarding.FormMappers;
import com.ihrms.onboarding.HtmlPdfRenderer;
import com.ihrms.onboarding.dto.OnboardingDtos;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumSet;
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
 * Offboarding documents (§3.6 stage 2): for a HIERARCHY-APPROVED case, HR sends employee-facing documents
 * (Exit Formalities, Settlement, Separation) with per-case values; the employee reads/fills/signs them in the
 * workspace; HR verifies or sends them back (the Form-4 loop). Rendering + storage reuse the agreements
 * pipeline (single-source fragments + the letterhead-slot template). Notifications fire controller-after-commit.
 */
@Service
public class OffboardingDocumentService {

  private static final Logger log = LoggerFactory.getLogger(OffboardingDocumentService.class);
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final int DOWNLOAD_TTL_SECONDS = 300;
  private static final List<OffboardingDocType> PACK =
      List.of(OffboardingDocType.EXIT_FORMALITIES, OffboardingDocType.SETTLEMENT, OffboardingDocType.SEPARATION);

  /** The seed text for the Settlement per-case terms line (HR edits it). */
  static final String SETTLEMENT_TERMS_SEED =
      "The First Party, in receipt of the September Month Salary (Salary) will be released after all the"
          + " deductions (Provident Fund, Professional Tax, Health Insurance), as per the working days, hereby"
          + " declares and warrants that this Agreement is being executed at his free will.";

  private final EmployeeRepository employees;
  private final OffboardingCaseRepository cases;
  private final OffboardingDocumentRepository docs;
  private final CompanyRepository companies;
  private final UserRepository users;
  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final NotificationRepository notifications;
  private final AuthorizationService authz;
  private final StorageService storage;
  private final HtmlPdfRenderer html;
  private final OffboardingDocumentTemplates templates;
  private final AuditService audit;
  private final MailService mail;
  private final PushService push;

  public OffboardingDocumentService(
      EmployeeRepository employees,
      OffboardingCaseRepository cases,
      OffboardingDocumentRepository docs,
      CompanyRepository companies,
      UserRepository users,
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      NotificationRepository notifications,
      AuthorizationService authz,
      StorageService storage,
      HtmlPdfRenderer html,
      OffboardingDocumentTemplates templates,
      AuditService audit,
      MailService mail,
      PushService push) {
    this.employees = employees;
    this.cases = cases;
    this.docs = docs;
    this.companies = companies;
    this.users = users;
    this.form1s = form1s;
    this.form2s = form2s;
    this.notifications = notifications;
    this.authz = authz;
    this.storage = storage;
    this.html = html;
    this.templates = templates;
    this.audit = audit;
    this.mail = mail;
    this.push = push;
  }

  // --- HR: prepare / send ---------------------------------------------------

  /** The record documents section (HR/COMPANY_ADMIN/SUPER_ADMIN): current statuses + the send specs. */
  @Transactional(readOnly = true)
  public RecordDocuments recordDocuments(IhrmsPrincipal.User actor, String employeeId) {
    authz.assertCanAccessEmployee(actor, employeeId);
    Employee employee = employees.findById(employeeId).orElseThrow(() -> notFound("Employee not found"));
    OffboardingCase c = approvedCase(employeeId);
    if (c == null) {
      return new RecordDocuments(List.of(), List.of());
    }
    Set<OffboardingDocType> existing = existingTypes(c.getId());
    List<DocSummary> summaries = summaries(c.getId());
    List<SendableDoc> sendable = new ArrayList<>();
    for (OffboardingDocType type : PACK) {
      sendable.add(
          new SendableDoc(type, templates.title(type), existing.contains(type), hrFields(type, employee, c)));
    }
    return new RecordDocuments(summaries, sendable);
  }

  /** HR sends the selected documents for an APPROVED case (per-type idempotency, like the agreements pack). */
  @Transactional
  public SendDocumentsResult send(
      IhrmsPrincipal.User actor, String employeeId, SendDocumentsRequest req, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    OffboardingCase c =
        cases
            .findFirstByEmployeeIdAndStatusIn(
                employeeId, List.of(OffboardingStatus.PENDING_APPROVAL, OffboardingStatus.APPROVED))
            .orElseThrow(() -> conflict("This employee has no active offboarding case"));
    if (c.getStatus() != OffboardingStatus.APPROVED) {
      throw conflict("Documents can only be sent once the offboarding case is approved");
    }
    if (req == null || req.documents() == null || req.documents().isEmpty()) {
      throw badRequest("Select at least one document to send");
    }
    Set<OffboardingDocType> existing = existingTypes(c.getId());
    List<SendDocSelection> toCreate =
        req.documents().stream()
            .filter(d -> d.type() != null && !existing.contains(d.type()))
            .toList();
    if (toCreate.isEmpty()) {
      throw conflict("The selected documents have already been sent");
    }
    for (SendDocSelection sel : toCreate) {
      Map<String, String> hrValues = sel.hrValues() == null ? Map.of() : sel.hrValues();
      validateHrValues(sel.type(), employee, c, hrValues);
      OffboardingDocument d = new OffboardingDocument();
      d.setCaseId(c.getId());
      d.setType(sel.type());
      d.setStatus(OffboardingDocStatus.PENDING);
      d.setSentByUserId(actor.userId());
      d.setHrValues(normalizeHrValues(sel.type(), employee, c, hrValues));
      docs.save(d);
    }
    audit.record(
        AuditActor.from(actor),
        "OFFBOARDING_DOCS_SENT",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("types", toCreate.stream().map(s -> s.type().name()).toList()),
        ip);
    return new SendDocumentsResult(summaries(c.getId()));
  }

  /** Best-effort employee notice after send commits: mail + push (workspace deep-link). Never throws. */
  public void notifyEmployeeAfterSend(String employeeId) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null) {
        return;
      }
      mail.sendOffboardingDocsAssigned(employee.getEmail(), employee.getFullName());
      push.sendToPrincipal(
          PrincipalRef.forEmployee(employee.getId(), employee.getCompanyId()),
          "Offboarding documents",
          "Documents have been sent for you to read and sign.",
          "/workspace/offboarding");
    } catch (RuntimeException e) {
      log.warn("Post-send offboarding-doc notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- HR: verify / send-back -----------------------------------------------

  @Transactional
  public RecordDocuments verify(IhrmsPrincipal.User actor, String employeeId, OffboardingDocType type, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    OffboardingDocument d = loadSubmitted(employeeId, type);
    d.setStatus(OffboardingDocStatus.VERIFIED);
    d.setVerifiedAt(java.time.Instant.now());
    d.setRevisionNote(null);
    docs.save(d);
    audit.record(
        AuditActor.from(actor), "OFFBOARDING_DOC_VERIFIED", "Employee", employee.getId(),
        Map.<String, Object>of("type", type.name()), ip);
    return recordDocumentsUnscoped(employeeId);
  }

  @Transactional
  public RecordDocuments sendBack(
      IhrmsPrincipal.User actor, String employeeId, OffboardingDocType type, String note, String ip) {
    if (note == null || note.isBlank()) {
      throw badRequest("A note is required to send a document back");
    }
    Employee employee = loadOwn(actor, employeeId);
    OffboardingDocument d = loadSubmitted(employeeId, type);
    d.setStatus(OffboardingDocStatus.REVISION_REQUESTED);
    d.setRevisionNote(note);
    docs.save(d);
    audit.record(
        AuditActor.from(actor), "OFFBOARDING_DOC_SENT_BACK", "Employee", employee.getId(),
        Map.<String, Object>of("type", type.name(), "note", note), ip);
    return recordDocumentsUnscoped(employeeId);
  }

  /** Best-effort employee notice after a send-back commits: mail + push with the note. Never throws. */
  public void notifyEmployeeAfterSendBack(String employeeId, OffboardingDocType type, String note) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null) {
        return;
      }
      mail.sendOffboardingDocReturned(employee.getEmail(), employee.getFullName(), templates.title(type), note);
      push.sendToPrincipal(
          PrincipalRef.forEmployee(employee.getId(), employee.getCompanyId()),
          "Document needs changes",
          "Your " + templates.title(type) + " was sent back — please update it.",
          "/workspace/offboarding");
    } catch (RuntimeException e) {
      log.warn("Post-send-back offboarding notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- Employee: read + complete --------------------------------------------

  @Transactional(readOnly = true)
  public List<MyDocSummary> myDocuments(IhrmsPrincipal.Employee emp) {
    OffboardingCase c = approvedCase(emp.employeeId());
    if (c == null) {
      return List.of();
    }
    return docs.findByCaseIdOrderByTypeAsc(c.getId()).stream()
        .map(d -> new MyDocSummary(d.getType(), templates.title(d.getType()), d.getStatus(), d.getRevisionNote()))
        .toList();
  }

  @Transactional(readOnly = true)
  public MyDocView myDocument(IhrmsPrincipal.Employee emp, OffboardingDocType type) {
    OffboardingCase c = approvedCase(emp.employeeId());
    OffboardingDocument d =
        c == null ? null : docs.findByCaseIdAndType(c.getId(), type).orElse(null);
    if (d == null) {
      throw notFound("Document not found");
    }
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    String bodyHtml = renderReadView(d, employee);
    return new MyDocView(
        type,
        templates.title(type),
        d.getStatus(),
        bodyHtml,
        d.getRevisionNote(),
        downloadUrl(d),
        employeeFields(type, employee));
  }

  @Transactional
  public CompleteDocResult complete(
      IhrmsPrincipal.Employee emp, OffboardingDocType type, CompleteDocRequest body, String ip) {
    OffboardingCase c = approvedCase(emp.employeeId());
    OffboardingDocument d = c == null ? null : docs.findByCaseIdAndType(c.getId(), type).orElse(null);
    if (d == null) {
      throw notFound("Document not found");
    }
    if (d.getStatus() != OffboardingDocStatus.PENDING
        && d.getStatus() != OffboardingDocStatus.REVISION_REQUESTED) {
      throw conflict("This document is not awaiting your action");
    }
    if (body == null || !body.consentAccepted()) {
      throw badRequest("You must confirm you have read and agree to the terms");
    }
    String signatureDataUrl = validateSignature(body.signatureDataUrl());
    Map<String, String> fill = body.fillValues() == null ? Map.of() : body.fillValues();

    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    validateFillValues(type, employee, fill);
    byte[] pdf = html.render("agreement", pdfModel(renderPdfBody(d, employee, fill, signatureDataUrl)));
    String key =
        storage.buildKey(
            employee.getCompanyId(), employee.getId(), "offboarding", type.name().toLowerCase() + ".pdf");
    storage.putObject(key, pdf, "application/pdf");

    d.setStatus(OffboardingDocStatus.SUBMITTED);
    d.setSubmittedAt(java.time.Instant.now());
    d.setStorageKey(key);
    d.setRevisionNote(null);
    docs.save(d);

    // Durable trail for the sending HR (push after commit; no bell feed).
    Notification n = new Notification();
    n.setRecipientUserId(d.getSentByUserId());
    n.setType(NotificationType.OFFBOARDING_DOC_SUBMITTED);
    n.setEmployeeId(employee.getId());
    notifications.save(n);

    audit.record(
        AuditActor.from(emp),
        "OFFBOARDING_DOC_SUBMITTED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("type", type.name(), "sha256", Hashing.sha256Hex(pdf), "storageKey", key),
        ip);
    return new CompleteDocResult(type, d.getStatus(), downloadUrl(d));
  }

  /** Best-effort HR notice after an employee submits: push to the sending HR. Never throws. */
  public void notifyHrAfterSubmit(String employeeId, OffboardingDocType type) {
    try {
      OffboardingCase c = approvedCase(employeeId);
      OffboardingDocument d = c == null ? null : docs.findByCaseIdAndType(c.getId(), type).orElse(null);
      Employee employee = employees.findById(employeeId).orElse(null);
      if (d == null || employee == null) {
        return;
      }
      User hr = users.findById(d.getSentByUserId()).orElse(null);
      if (hr == null) {
        return;
      }
      String who = employee.getFullName() == null ? "An employee" : employee.getFullName();
      push.sendToPrincipal(
          PrincipalRef.forUser(hr.getId(), employee.getCompanyId()),
          "Document submitted",
          who + " submitted the " + templates.title(type) + ".",
          "/hr/employees/" + employee.getId());
    } catch (RuntimeException e) {
      log.warn("Post-submit HR notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- internals: rendering + tokens ---------------------------------------

  private String renderReadView(OffboardingDocument d, Employee employee) {
    Map<String, String> t = commonTokens(d, employee);
    // Employee fill spots shown as prefills (read preview); signature/date left blank.
    for (FieldView f : employeeFields(d.getType(), employee)) {
      t.put(f.key(), f.value());
    }
    t.putIfAbsent("SIGN_DATE", "");
    return templates.render(d.getType(), t, "");
  }

  private String renderPdfBody(
      OffboardingDocument d, Employee employee, Map<String, String> fill, String signatureDataUrl) {
    Map<String, String> t = commonTokens(d, employee);
    for (FieldView f : employeeFields(d.getType(), employee)) {
      // Edited fill values are stamped into the PDF only; fall back to the prefill when omitted.
      String v = fill.get(f.key());
      t.put(f.key(), v != null && !v.isBlank() ? v : f.value());
    }
    t.put("SIGN_DATE", LocalDate.now().format(DMY));
    String sigImg = "<img class=\"sig\" src=\"" + signatureDataUrl + "\" alt=\"signature\"/>";
    return templates.render(d.getType(), t, sigImg);
  }

  /** Company/HR/HR-values tokens shared by read + PDF. */
  private Map<String, String> commonTokens(OffboardingDocument d, Employee employee) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("COMPANY_NAME", companyName(employee));
    t.put("HR_NAME", users.findById(d.getSentByUserId()).map(User::getName).orElse(""));
    t.put("EMPLOYEE_NAME", nn(employee.getFullName()));
    if (d.getHrValues() != null) {
      d.getHrValues().forEach((k, v) -> t.put(k, v == null ? "" : String.valueOf(v)));
    }
    return t;
  }

  private Map<String, Object> pdfModel(String bodyHtml) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("bodyHtml", bodyHtml);
    m.put("footerText", "Private & Confidential");
    return m;
  }

  // --- internals: per-type field specs -------------------------------------

  /** The HR-at-send fields for a type, with computed defaults. */
  private List<FieldView> hrFields(OffboardingDocType type, Employee employee, OffboardingCase c) {
    String joining = employee.getDateOfJoining() == null ? "" : employee.getDateOfJoining().format(DMY);
    String lastDay = c.getLastWorkingDay() == null ? "" : c.getLastWorkingDay().format(DMY);
    String designation = designation(employee);
    return switch (type) {
      case EXIT_FORMALITIES -> List.of();
      case SETTLEMENT -> List.of(
          new FieldView("AGREEMENT_DATE", "Agreement date", FieldKind.TEXT, LocalDate.now().format(DMY), true),
          new FieldView("EMPLOYMENT_START", "Employment start", FieldKind.TEXT, joining, true),
          new FieldView("LAST_DATE", "Last date", FieldKind.TEXT, lastDay, true),
          new FieldView("DESIGNATION", "Designation", FieldKind.TEXT, designation, true),
          new FieldView(
              "SETTLEMENT_TERMS_LINE", "Settlement terms", FieldKind.MULTILINE, SETTLEMENT_TERMS_SEED, true));
      case SEPARATION -> List.of(
          new FieldView("RESIGNED_DATE", "Resigned date", FieldKind.TEXT, lastDay, true),
          new FieldView("EMPLOYMENT_AGREEMENT_DATE", "Employment agreement date", FieldKind.TEXT, joining, true),
          new FieldView("GARDEN_LEAVE_START", "Garden-leave start", FieldKind.TEXT, "", true),
          new FieldView("PAYMENT_AMOUNT", "Payment amount", FieldKind.TEXT, "Rs. ", true),
          new FieldView("COMPANY_CAR", "Company car", FieldKind.TEXT, "[NA]", false),
          new FieldView("CAR_PLATE", "Car plate number", FieldKind.TEXT, "[NA]", false));
    };
  }

  /** The employee fill fields for a type, with prefills. */
  private List<FieldView> employeeFields(OffboardingDocType type, Employee employee) {
    Form1View v1 = form1(employee);
    String address = v1 == null ? "" : nn(v1.currentAddress());
    return switch (type) {
      case EXIT_FORMALITIES -> List.of();
      case SETTLEMENT -> List.of(
          new FieldView("FATHER_NAME", "Father's name (S/O)", FieldKind.TEXT, "", true),
          new FieldView("AGE", "Age", FieldKind.TEXT, ageFrom(v1), true),
          new FieldView("ADDRESS", "Residential address", FieldKind.TEXT, address, true));
      case SEPARATION -> List.of(
          new FieldView("ADDRESS", "Residential address", FieldKind.TEXT, address, true),
          new FieldView("PLACE", "Place", FieldKind.TEXT, "Hyderabad", true));
    };
  }

  private void validateHrValues(
      OffboardingDocType type, Employee employee, OffboardingCase c, Map<String, String> values) {
    for (FieldView f : hrFields(type, employee, c)) {
      if (f.required() && (values.get(f.key()) == null || values.get(f.key()).isBlank())) {
        throw badRequest("Missing required value: " + f.label());
      }
    }
  }

  /** Fill in optional HR defaults ([NA] for car/plate) that HR left blank; stored as JSONB. */
  private Map<String, Object> normalizeHrValues(
      OffboardingDocType type, Employee employee, OffboardingCase c, Map<String, String> values) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (FieldView f : hrFields(type, employee, c)) {
      String v = values.get(f.key());
      out.put(f.key(), v != null && !v.isBlank() ? v : f.value());
    }
    return out;
  }

  private void validateFillValues(OffboardingDocType type, Employee employee, Map<String, String> fill) {
    for (FieldView f : employeeFields(type, employee)) {
      if (f.required() && (fill.get(f.key()) == null || fill.get(f.key()).isBlank())) {
        throw badRequest("Missing required value: " + f.label());
      }
    }
  }

  // --- internals: loading + scoping ----------------------------------------

  private OffboardingCase approvedCase(String employeeId) {
    return cases
        .findFirstByEmployeeIdAndStatusIn(
            employeeId, List.of(OffboardingStatus.PENDING_APPROVAL, OffboardingStatus.APPROVED))
        .filter(c -> c.getStatus() == OffboardingStatus.APPROVED)
        .orElse(null);
  }

  private OffboardingDocument loadSubmitted(String employeeId, OffboardingDocType type) {
    OffboardingCase c = approvedCase(employeeId);
    OffboardingDocument d = c == null ? null : docs.findByCaseIdAndType(c.getId(), type).orElse(null);
    if (d == null) {
      throw notFound("Document not found");
    }
    if (d.getStatus() != OffboardingDocStatus.SUBMITTED) {
      throw conflict("Only a submitted document can be verified or sent back");
    }
    return d;
  }

  private RecordDocuments recordDocumentsUnscoped(String employeeId) {
    Employee employee = employees.findById(employeeId).orElseThrow();
    OffboardingCase c = approvedCase(employeeId);
    if (c == null) {
      return new RecordDocuments(List.of(), List.of());
    }
    Set<OffboardingDocType> existing = existingTypes(c.getId());
    List<SendableDoc> sendable = new ArrayList<>();
    for (OffboardingDocType type : PACK) {
      sendable.add(
          new SendableDoc(type, templates.title(type), existing.contains(type), hrFields(type, employee, c)));
    }
    return new RecordDocuments(summaries(c.getId()), sendable);
  }

  private Set<OffboardingDocType> existingTypes(String caseId) {
    return docs.findByCaseId(caseId).stream()
        .map(OffboardingDocument::getType)
        .collect(java.util.stream.Collectors.toCollection(() -> EnumSet.noneOf(OffboardingDocType.class)));
  }

  private List<DocSummary> summaries(String caseId) {
    return docs.findByCaseIdOrderByTypeAsc(caseId).stream()
        .map(
            d ->
                new DocSummary(
                    d.getType(),
                    templates.title(d.getType()),
                    d.getStatus(),
                    iso(d.getSentAt()),
                    iso(d.getSubmittedAt()),
                    iso(d.getVerifiedAt()),
                    d.getRevisionNote(),
                    users.findById(d.getSentByUserId()).map(User::getName).orElse(null),
                    downloadUrl(d)))
        .toList();
  }

  private String downloadUrl(OffboardingDocument d) {
    return d.getStorageKey() == null ? null : storage.presignedGetUrl(d.getStorageKey(), DOWNLOAD_TTL_SECONDS);
  }

  private Employee loadOwn(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee =
        employees.findById(employeeId).orElseThrow(() -> notFound("Employee not found"));
    boolean own =
        employee.getCompanyId().equals(actor.companyId())
            && employee.getOnboardingHrId().equals(actor.userId());
    if (!own) {
      throw notFound("Employee not found"); // anti-enumeration, like verification/approve
    }
    return employee;
  }

  // --- small helpers --------------------------------------------------------

  private String companyName(Employee employee) {
    return companies.findById(employee.getCompanyId()).map(Company::getName).orElse("Company");
  }

  private String designation(Employee employee) {
    Form2View v2 =
        form2s.findByEmployeeId(employee.getId()).map(f -> FormMappers.form2View(f, employee.getEmployeeCode())).orElse(null);
    String d = v2 == null ? null : v2.designation();
    return d != null && !d.isBlank() ? d : nn(employee.getDesignation());
  }

  private Form1View form1(Employee employee) {
    Form1Personal f1 = form1s.findByEmployeeId(employee.getId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    return f1 == null ? null : FormMappers.form1View(f1, f2, FormMappers.Mode.PLAIN);
  }

  private String ageFrom(Form1View v1) {
    if (v1 == null || v1.dateOfBirth() == null || v1.dateOfBirth().isBlank()) {
      return "";
    }
    try {
      LocalDate dob = LocalDate.parse(v1.dateOfBirth().substring(0, 10));
      return String.valueOf(Period.between(dob, LocalDate.now()).getYears());
    } catch (RuntimeException e) {
      return "";
    }
  }

  private String validateSignature(String dataUrl) {
    if (dataUrl == null || dataUrl.isBlank()) {
      throw badRequest("A signature is required");
    }
    int comma = dataUrl.indexOf(',');
    if (!dataUrl.startsWith("data:image/") || comma < 0 || !dataUrl.substring(0, comma).contains("base64")) {
      throw badRequest("Invalid signature image");
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
      if (bytes.length == 0 || bytes.length > OnboardingDtos.MAX_UPLOAD_BYTES) {
        throw badRequest("Signature image is empty or too large");
      }
    } catch (IllegalArgumentException e) {
      throw badRequest("Could not read the signature image");
    }
    return dataUrl;
  }

  private static String iso(java.time.Instant i) {
    return i == null ? null : i.toString();
  }

  private static String nn(String s) {
    return s == null ? "" : s;
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
