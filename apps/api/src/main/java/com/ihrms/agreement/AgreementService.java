package com.ihrms.agreement;

import com.ihrms.agreement.dto.AgreementDtos.AgreementPrefill;
import com.ihrms.agreement.dto.AgreementDtos.AgreementSummary;
import com.ihrms.agreement.dto.AgreementDtos.CompleteAgreementRequest;
import com.ihrms.agreement.dto.AgreementDtos.CompleteAgreementResult;
import com.ihrms.agreement.dto.AgreementDtos.MyAgreementSummary;
import com.ihrms.agreement.dto.AgreementDtos.MyAgreementView;
import com.ihrms.agreement.dto.AgreementDtos.SendAgreementsResult;
import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.AgreementStatus;
import com.ihrms.domain.enums.EmployeeAgreementType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.EmployeeAgreement;
import com.ihrms.domain.model.Form1Personal;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Notification;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeAgreementRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form1PersonalRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.NotificationRepository;
import com.ihrms.domain.repository.UserRepository;
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
import java.time.format.DateTimeFormatter;
import java.util.Base64;
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
 * Post-approval agreements (§Agreements). After an employee is APPROVED, HR SENDS a standard pack of three
 * company agreements (AUP, NDA, Notice Period). The employee reads each in full, fills the few blanks, signs,
 * and submits; a PDF is rendered per agreement (single-source body fragment + substitutions + signature +
 * date), stored under the employee record, and the sending HR is notified. No verification loop.
 */
@Service
public class AgreementService {

  private static final Logger log = LoggerFactory.getLogger(AgreementService.class);
  private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
  private static final DateTimeFormatter MONTH_YEAR = DateTimeFormatter.ofPattern("MMMM yyyy");
  private static final int DOWNLOAD_TTL_SECONDS = 300;
  /** The three-agreement pack, always in this fixed order. */
  private static final List<EmployeeAgreementType> PACK =
      List.of(EmployeeAgreementType.AUP, EmployeeAgreementType.NDA, EmployeeAgreementType.NOTICE_PERIOD);

  private final EmployeeRepository employees;
  private final EmployeeAgreementRepository agreements;
  private final CompanyRepository companies;
  private final UserRepository users;
  private final Form1PersonalRepository form1s;
  private final Form2InfoRepository form2s;
  private final NotificationRepository notifications;
  private final StorageService storage;
  private final HtmlPdfRenderer html;
  private final AgreementTemplates templates;
  private final AuditService audit;
  private final MailService mail;
  private final PushService push;

  public AgreementService(
      EmployeeRepository employees,
      EmployeeAgreementRepository agreements,
      CompanyRepository companies,
      UserRepository users,
      Form1PersonalRepository form1s,
      Form2InfoRepository form2s,
      NotificationRepository notifications,
      StorageService storage,
      HtmlPdfRenderer html,
      AgreementTemplates templates,
      AuditService audit,
      MailService mail,
      PushService push) {
    this.employees = employees;
    this.agreements = agreements;
    this.companies = companies;
    this.users = users;
    this.form1s = form1s;
    this.form2s = form2s;
    this.notifications = notifications;
    this.storage = storage;
    this.html = html;
    this.templates = templates;
    this.audit = audit;
    this.mail = mail;
    this.push = push;
  }

  // --- HR: send the standard pack -------------------------------------------

  /**
   * HR sends the three-agreement pack to an APPROVED employee (§Agreements). Onboarding-HR-scoped (the
   * controller is HR-only and the employee's onboardingHrId must match). 409 if not APPROVED or already sent.
   */
  @Transactional
  public SendAgreementsResult send(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadOwn(actor, employeeId);
    if (employee.getStatus() != EmployeeStatus.APPROVED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Agreements can only be sent to an APPROVED employee");
    }
    if (agreements.existsByEmployeeId(employee.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Agreements have already been sent to this employee");
    }
    for (EmployeeAgreementType type : PACK) {
      EmployeeAgreement a = new EmployeeAgreement();
      a.setEmployeeId(employee.getId());
      a.setType(type);
      a.setStatus(AgreementStatus.PENDING);
      a.setSentByUserId(actor.userId());
      agreements.save(a);
    }
    audit.record(
        AuditActor.from(actor),
        "AGREEMENTS_SENT",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("types", PACK.stream().map(Enum::name).toList()),
        ip);
    return new SendAgreementsResult(summaries(employee.getId(), actor.name()));
  }

  /** Best-effort employee notice AFTER the send commits (controller-called): email + OS push. Never throws. */
  public void notifyEmployeeAfterSend(String employeeId) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null) {
        return;
      }
      mail.sendAgreementsAssigned(employee.getEmail(), employee.getFullName());
      push.sendToPrincipal(
          PrincipalRef.forEmployee(employee.getId(), employee.getCompanyId()),
          "Agreements to sign",
          "Your employer has sent standard agreements for you to review and sign.",
          // Agreements live in the WORKSPACE (§3.5) — this non-slugged area path resolves to the
          // employee's slugged workspace home (LegacyRedirect), where the Agreements card awaits.
          "/workspace/agreements");
    } catch (RuntimeException e) {
      log.warn("Post-send agreement notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- Employee: read + complete --------------------------------------------

  /** The employee's own agreements (fixed pack order); empty until HR sends. */
  @Transactional(readOnly = true)
  public List<MyAgreementSummary> myAgreements(IhrmsPrincipal.Employee emp) {
    return agreements.findByEmployeeIdOrderByTypeAsc(emp.employeeId()).stream()
        .map(
            a ->
                new MyAgreementSummary(
                    a.getType(),
                    templates.title(a.getType()),
                    a.getStatus(),
                    a.getCompletedAt() == null ? null : a.getCompletedAt().toString()))
        .toList();
  }

  /** A single agreement to read and fill: full body text (read view) + prefills + status. */
  @Transactional(readOnly = true)
  public MyAgreementView myAgreement(IhrmsPrincipal.Employee emp, EmployeeAgreementType type) {
    EmployeeAgreement a = loadMine(emp.employeeId(), type);
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    Prefills p = prefills(employee);
    String hrName = users.findById(a.getSentByUserId()).map(User::getName).orElse("");
    String bodyHtml = renderReadView(type, employee, p, hrName);
    return new MyAgreementView(
        type,
        templates.title(type),
        a.getStatus(),
        bodyHtml,
        a.getSentAt() == null ? null : a.getSentAt().toString(),
        a.getCompletedAt() == null ? null : a.getCompletedAt().toString(),
        downloadUrl(a),
        new AgreementPrefill(p.fullName, p.employeeCode, p.designation, p.address, p.mobile));
  }

  /**
   * The employee signs and submits one agreement (§Agreements). PENDING-only; consent must be accepted;
   * AUP requires a 12-digit Aadhaar (stored encrypted). Renders + stores the PDF, marks COMPLETED. The
   * sending-HR notification is durable (a Notification row) here; push + email fire post-commit.
   */
  @Transactional
  public CompleteAgreementResult complete(
      IhrmsPrincipal.Employee emp, EmployeeAgreementType type, CompleteAgreementRequest body, String ip) {
    EmployeeAgreement a = loadMine(emp.employeeId(), type);
    if (a.getStatus() != AgreementStatus.PENDING) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This agreement is already completed");
    }
    if (body == null || !body.consentAccepted()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "You must confirm you have read and agree to the terms");
    }
    String signatureDataUrl = validateSignature(body.signatureDataUrl());

    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    Prefills p = prefills(employee);
    // {{HR_NAME}} on the NDA company block is the SENDING HR (not the acting employee).
    p.hrNameForPdf = users.findById(a.getSentByUserId()).map(User::getName).orElse("");

    // Edited prefills are stamped into the PDF ONLY (never written back to the form data).
    String designation = firstNonBlank(body.designation(), p.designation);
    String address = firstNonBlank(body.address(), p.address);
    String mobile = firstNonBlank(body.mobile(), p.mobile);

    String aadhaarDigits = null;
    if (type == EmployeeAgreementType.AUP) {
      aadhaarDigits = validateAadhaar(body.aadhaar());
    }

    // Render the PDF from the SAME single-source fragment, all tokens filled.
    LocalDate today = LocalDate.now();
    String bodyHtml =
        renderPdfBody(type, employee, p, designation, address, mobile, aadhaarDigits, signatureDataUrl, today);
    byte[] pdf = html.render("agreement", pdfModel(bodyHtml));

    String key =
        storage.buildKey(
            employee.getCompanyId(), employee.getId(), "agreements", type.name().toLowerCase() + ".pdf");
    storage.putObject(key, pdf, "application/pdf");

    a.setStatus(AgreementStatus.COMPLETED);
    a.setCompletedAt(java.time.Instant.now());
    a.setStorageKey(key);
    agreements.save(a);

    if (aadhaarDigits != null) {
      employee.setAadhaarNumber(aadhaarDigits); // encrypted at rest via the field converter (§6)
      employees.save(employee);
    }

    // Durable record for the sending HR (push + email fire post-commit from the controller).
    Notification n = new Notification();
    n.setRecipientUserId(a.getSentByUserId());
    n.setType(NotificationType.AGREEMENT_COMPLETED);
    n.setEmployeeId(employee.getId());
    notifications.save(n);

    audit.record(
        AuditActor.from(emp),
        "AGREEMENT_COMPLETED",
        "Employee",
        employee.getId(),
        Map.<String, Object>of("type", type.name(), "sha256", Hashing.sha256Hex(pdf), "storageKey", key),
        ip);

    return new CompleteAgreementResult(
        type, a.getStatus(), a.getCompletedAt().toString(), downloadUrl(a));
  }

  /** Best-effort HR notice AFTER completion commits (controller-called): OS push + email. Never throws. */
  public void notifyHrAfterComplete(String employeeId, EmployeeAgreementType type) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      EmployeeAgreement a = employee == null ? null : loadMineOrNull(employeeId, type);
      if (employee == null || a == null) {
        return;
      }
      User hr = users.findById(a.getSentByUserId()).orElse(null);
      if (hr == null) {
        return;
      }
      String who = employee.getFullName() == null ? "An employee" : employee.getFullName();
      String title = templates.title(type);
      push.sendToPrincipal(
          PrincipalRef.forUser(hr.getId(), employee.getCompanyId()),
          "Agreement signed",
          who + " completed the " + title + ".",
          "/hr/employees/" + employee.getId());
      mail.sendAgreementCompleted(hr.getEmail(), who, title);
    } catch (RuntimeException e) {
      log.warn("Post-completion HR notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- HR record view -------------------------------------------------------

  /** The agreement summaries for an employee's HR record (any caller already scoped by the assembler). */
  @Transactional(readOnly = true)
  public List<AgreementSummary> forRecord(String employeeId) {
    return summaries(employeeId, null);
  }

  // --- internals ------------------------------------------------------------

  private List<AgreementSummary> summaries(String employeeId, String actorNameFallback) {
    return agreements.findByEmployeeIdOrderByTypeAsc(employeeId).stream()
        .map(
            a -> {
              String sentByName =
                  users.findById(a.getSentByUserId()).map(User::getName).orElse(actorNameFallback);
              return new AgreementSummary(
                  a.getType(),
                  templates.title(a.getType()),
                  a.getStatus(),
                  a.getSentAt() == null ? null : a.getSentAt().toString(),
                  a.getCompletedAt() == null ? null : a.getCompletedAt().toString(),
                  sentByName,
                  downloadUrl(a));
            })
        .toList();
  }

  private String downloadUrl(EmployeeAgreement a) {
    if (a.getStorageKey() == null) {
      return null;
    }
    return storage.presignedGetUrl(a.getStorageKey(), DOWNLOAD_TTL_SECONDS);
  }

  private String renderReadView(
      EmployeeAgreementType type, Employee employee, Prefills p, String hrName) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("COMPANY_NAME", companyName(employee));
    t.put("HR_NAME", hrName);
    t.put("EMPLOYEE_NAME", p.fullName);
    t.put("EMPLOYEE_ID", p.employeeCode);
    t.put("DESIGNATION", p.designation);
    t.put("ADDRESS", p.address);
    t.put("MOBILE", p.mobile);
    // Not-yet-provided fields render as visible blanks in the reading view.
    t.put("AADHAAR", "");
    t.put("SIGN_DATE", "");
    t.put("SIGN_DATE_LONG", "________________");
    return templates.render(type, t, ""); // no signature image in the read view
  }

  private String renderPdfBody(
      EmployeeAgreementType type,
      Employee employee,
      Prefills p,
      String designation,
      String address,
      String mobile,
      String aadhaarDigits,
      String signatureDataUrl,
      LocalDate today) {
    Map<String, String> t = new LinkedHashMap<>();
    t.put("COMPANY_NAME", companyName(employee));
    t.put("HR_NAME", p.hrNameForPdf); // the sending HR, resolved in complete()
    t.put("EMPLOYEE_NAME", p.fullName);
    t.put("EMPLOYEE_ID", p.employeeCode);
    t.put("DESIGNATION", designation);
    t.put("ADDRESS", address);
    t.put("MOBILE", mobile);
    t.put("AADHAAR", aadhaarDigits == null ? "" : formatAadhaar(aadhaarDigits));
    t.put("SIGN_DATE", today.format(DMY));
    t.put("SIGN_DATE_LONG", ordinalDay(today.getDayOfMonth()) + " day of " + today.format(MONTH_YEAR));
    String sigImg = "<img class=\"sig\" src=\"" + signatureDataUrl + "\" alt=\"signature\"/>";
    return templates.render(type, t, sigImg);
  }

  private Map<String, Object> pdfModel(String bodyHtml) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("bodyHtml", bodyHtml);
    m.put("footerText", "Private & Confidential");
    return m;
  }

  private String companyName(Employee employee) {
    return companies.findById(employee.getCompanyId()).map(Company::getName).orElse("Company");
  }

  /** Prefill values from the employee + submitted forms; edited copies are stamped into the PDF only. */
  private Prefills prefills(Employee employee) {
    Form1Personal f1 = form1s.findByEmployeeId(employee.getId()).orElse(null);
    Form2Info f2 = form2s.findByEmployeeId(employee.getId()).orElse(null);
    Form1View v1 = f1 == null ? null : FormMappers.form1View(f1, f2, FormMappers.Mode.PLAIN);
    Form2View v2 = f2 == null ? null : FormMappers.form2View(f2, employee.getEmployeeCode());
    Prefills p = new Prefills();
    p.fullName = firstNonBlank(employee.getFullName(), v1 == null ? null : v1.name());
    p.employeeCode = nn(employee.getEmployeeCode());
    p.designation = firstNonBlank(v2 == null ? null : v2.designation(), employee.getDesignation());
    p.address = v1 == null ? "" : nn(v1.currentAddress());
    p.mobile = v1 == null ? "" : nn(v1.mobile());
    return p;
  }

  private Employee loadOwn(IhrmsPrincipal.User actor, String employeeId) {
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    // HR is scoped to the employees they onboarded (§6). A cross-HR/cross-company target is reported as
    // NOT_FOUND (anti-enumeration), matching the review workspace's own-scope behaviour.
    boolean own =
        employee.getCompanyId().equals(actor.companyId())
            && employee.getOnboardingHrId().equals(actor.userId());
    if (!own) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
    }
    return employee;
  }

  private EmployeeAgreement loadMine(String employeeId, EmployeeAgreementType type) {
    return agreements
        .findByEmployeeIdAndType(employeeId, type)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Agreement not found"));
  }

  private EmployeeAgreement loadMineOrNull(String employeeId, EmployeeAgreementType type) {
    return agreements.findByEmployeeIdAndType(employeeId, type).orElse(null);
  }

  private String validateSignature(String dataUrl) {
    if (dataUrl == null || dataUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A signature is required");
    }
    int comma = dataUrl.indexOf(',');
    if (!dataUrl.startsWith("data:image/") || comma < 0 || !dataUrl.substring(0, comma).contains("base64")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature image");
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
      if (bytes.length == 0 || bytes.length > OnboardingDtos.MAX_UPLOAD_BYTES) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Signature image is empty or too large");
      }
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the signature image");
    }
    return dataUrl;
  }

  /** Strip spaces/dashes; require exactly 12 digits (§Agreements Aadhaar). Returns the 12 digits. */
  private String validateAadhaar(String raw) {
    String digits = raw == null ? "" : raw.replaceAll("[\\s-]", "");
    if (!digits.matches("\\d{12}")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Aadhaar number must be exactly 12 digits");
    }
    return digits;
  }

  private static String formatAadhaar(String digits) {
    return digits.substring(0, 4) + " " + digits.substring(4, 8) + " " + digits.substring(8, 12);
  }

  private static String ordinalDay(int d) {
    if (d >= 11 && d <= 13) {
      return d + "th";
    }
    return switch (d % 10) {
      case 1 -> d + "st";
      case 2 -> d + "nd";
      case 3 -> d + "rd";
      default -> d + "th";
    };
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return nn(b);
  }

  private static String nn(String s) {
    return s == null ? "" : s;
  }

  /** Mutable prefill holder (kept package-simple; not exposed). */
  private static final class Prefills {
    String fullName = "";
    String employeeCode = "";
    String designation = "";
    String address = "";
    String mobile = "";
    String hrNameForPdf = ""; // NDA company block; set by complete() before PDF render
  }
}
