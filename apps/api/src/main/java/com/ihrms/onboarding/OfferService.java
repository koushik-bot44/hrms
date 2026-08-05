package com.ihrms.onboarding;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.OfferStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.EmployeeOffer;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeOfferRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.onboarding.dto.OfferDtos.AcceptOfferRequest;
import com.ihrms.onboarding.dto.OfferDtos.MyOfferView;
import com.ihrms.onboarding.dto.OfferDtos.OfferRecordView;
import com.ihrms.onboarding.dto.OfferDtos.OfferSummary;
import com.ihrms.onboarding.dto.OfferDtos.OfferTermsRequest;
import com.ihrms.onboarding.dto.OnboardingDtos;
import com.ihrms.onboarding.dto.OnboardingDtos.PresignedView;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import com.ihrms.storage.StorageService;
import com.ihrms.support.Hashing;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Offer Letter that opens onboarding (§3.2). At invite HR provides the terms ({@link #createOffer}); the
 * invited employee reads + signs + ACCEPTS it ({@link #accept}) before any form unlocks (the gate is
 * {@link #assertAccepted}, enforced in {@link OnboardingService}); the accepted PDF is stored on the record.
 * Rendering + storage reuse the agreements pipeline (single-source fragment → the letterhead-slot template).
 * The PDF carries the salary, so it is role-gated ({@link #recordPdfUrl}) — HR/COMPANY_ADMIN/SUPER_ADMIN +
 * the employee only, never manager/accountant/lookup.
 */
@Service
public class OfferService {

  private static final Logger log = LoggerFactory.getLogger(OfferService.class);
  private static final int VIEW_TTL_SECONDS = 60;
  private static final String DEFAULT_LOCATION = "Hyderabad";
  private static final DateTimeFormatter OFFER_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");
  private static final DateTimeFormatter APPOINTMENT_DATE =
      DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.ENGLISH);

  private final EmployeeRepository employees;
  private final EmployeeOfferRepository offers;
  private final CompanyRepository companies;
  private final UserRepository users;
  private final AuthorizationService authz;
  private final StorageService storage;
  private final HtmlPdfRenderer html;
  private final OfferTemplates templates;
  private final AuditService audit;
  private final MailService mail;
  private final PushService push;

  public OfferService(
      EmployeeRepository employees,
      EmployeeOfferRepository offers,
      CompanyRepository companies,
      UserRepository users,
      AuthorizationService authz,
      StorageService storage,
      HtmlPdfRenderer html,
      OfferTemplates templates,
      AuditService audit,
      MailService mail,
      PushService push) {
    this.employees = employees;
    this.offers = offers;
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

  // --- Invite: create the SENT offer ----------------------------------------

  /**
   * Create the SENT offer for a just-invited employee (called from the onboard flow). The terms snapshot
   * reuses the employee's joining date (from the Form-2 onboard) + HR's salary/location; the offer date is
   * today. One offer per employee.
   */
  public void createOffer(Employee employee, OfferTermsRequest terms, IhrmsPrincipal.User actor, String ip) {
    String location =
        terms.location() == null || terms.location().isBlank() ? DEFAULT_LOCATION : terms.location().trim();
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put(
        "joiningDate", employee.getDateOfJoining() == null ? "" : employee.getDateOfJoining().toString());
    snapshot.put("salary", terms.salary().trim());
    snapshot.put("location", location);
    snapshot.put("offerDate", LocalDate.now().toString());

    EmployeeOffer offer = new EmployeeOffer();
    offer.setEmployeeId(employee.getId());
    offer.setTerms(snapshot);
    offer.setStatus(OfferStatus.SENT);
    offers.save(offer);

    audit.record(
        new AuditActor("USER", actor.userId(), employee.getCompanyId()),
        "OFFER_SENT",
        "EmployeeOffer",
        offer.getId(),
        Map.of("employeeId", employee.getId()),
        ip);
  }

  // --- Employee: read + accept ----------------------------------------------

  /** The invited employee's offer screen — null when there is no offer (a pre-feature employee is ungated). */
  @Transactional(readOnly = true)
  public MyOfferView myOffer(IhrmsPrincipal.Employee emp) {
    EmployeeOffer offer = offers.findByEmployeeId(emp.employeeId()).orElse(null);
    if (offer == null) {
      return null;
    }
    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    String bodyHtml = templates.render(readTokens(employee, offer), "");
    return new MyOfferView(
        offer.getStatus(),
        bodyHtml,
        nn(employee.getFullName()),
        formatOfferDate(offer),
        iso(offer.getAcceptedAt()),
        downloadUrl(offer));
  }

  /** The invited employee accepts the offer: consent + a fresh signature → render + store the PDF, ACCEPTED. */
  @Transactional
  public void accept(IhrmsPrincipal.Employee emp, AcceptOfferRequest body, String ip) {
    EmployeeOffer offer =
        offers
            .findByEmployeeId(emp.employeeId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No offer to accept"));
    if (offer.getStatus() != OfferStatus.SENT) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This offer has already been accepted");
    }
    if (body == null || !body.consentAccepted()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "You must agree to the offer terms to accept");
    }
    String signatureDataUrl = validateSignature(body.signatureDataUrl());

    Employee employee = employees.findById(emp.employeeId()).orElseThrow();
    Map<String, String> tokens = readTokens(employee, offer);
    tokens.put("ACCEPT_DATE", LocalDate.now().format(OFFER_DATE));
    String sigImg = "<img class=\"sig\" src=\"" + signatureDataUrl + "\" alt=\"signature\"/>";
    byte[] pdf = html.render("agreement", pdfModel(templates.render(tokens, sigImg)));
    String key =
        storage.buildKey(employee.getCompanyId(), employee.getId(), "offer", "OFFER_LETTER.pdf");
    storage.putObject(key, pdf, "application/pdf");

    offer.setStatus(OfferStatus.ACCEPTED);
    offer.setAcceptedAt(java.time.Instant.now());
    offer.setStorageKey(key);
    offers.save(offer);

    audit.record(
        AuditActor.from(emp),
        "OFFER_ACCEPTED",
        "EmployeeOffer",
        offer.getId(),
        Map.of("employeeId", employee.getId(), "sha256", Hashing.sha256Hex(pdf), "storageKey", key),
        ip);
  }

  /** Best-effort notice to the onboarding HR after an offer is accepted (mail + push). Never throws. */
  public void notifyHrAfterAccept(String employeeId) {
    try {
      Employee employee = employees.findById(employeeId).orElse(null);
      if (employee == null || employee.getOnboardingHrId() == null) {
        return;
      }
      User hr = users.findById(employee.getOnboardingHrId()).orElse(null);
      String who = employee.getFullName() == null ? "An employee" : employee.getFullName();
      if (hr != null) {
        mail.sendOfferAccepted(hr.getEmail(), who);
      }
      push.sendToPrincipal(
          PrincipalRef.forUser(employee.getOnboardingHrId(), employee.getCompanyId()),
          "Offer accepted",
          who + " accepted their offer letter and can now start onboarding.",
          "/hr/employees/" + employee.getId());
    } catch (RuntimeException e) {
      log.warn("Post-accept offer notice skipped (best-effort): {}", e.getMessage());
    }
  }

  // --- The onboarding gate --------------------------------------------------

  /** 409 while an offer exists and is still SENT — the employee must accept before any form unlocks. */
  public void assertAccepted(String employeeId) {
    offers
        .findByEmployeeId(employeeId)
        .filter(o -> o.getStatus() == OfferStatus.SENT)
        .ifPresent(
            o -> {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "Accept your offer letter before continuing with onboarding");
            });
  }

  // --- Dashboard + record views ---------------------------------------------

  /** The lightweight offer state for the onboarding dashboard (null when there is no offer). */
  public OfferSummary dashboardOffer(String employeeId) {
    EmployeeOffer offer = offers.findByEmployeeId(employeeId).orElse(null);
    return offer == null
        ? null
        : new OfferSummary(offer.getStatus(), iso(offer.getAcceptedAt()), downloadUrl(offer));
  }

  /** The offer state for the HR record panel — status + dates only (no salary, no download URL). */
  public OfferRecordView recordOffer(String employeeId) {
    EmployeeOffer offer = offers.findByEmployeeId(employeeId).orElse(null);
    return offer == null
        ? null
        : new OfferRecordView(offer.getStatus(), formatOfferDate(offer), iso(offer.getAcceptedAt()));
  }

  /**
   * A presigned URL to the accepted offer PDF for a RECORD viewer (the salary lives in this PDF). Gated to
   * HR/COMPANY_ADMIN/SUPER_ADMIN via {@link AuthorizationService#assertCanAccessEmployee} + the URL rule —
   * manager/accountant/lookup never reach it. 404 until the offer is accepted.
   */
  @Transactional(readOnly = true)
  public PresignedView recordPdfUrl(IhrmsPrincipal.User actor, String employeeId, String ip) {
    authz.assertCanAccessEmployee(actor, employeeId);
    EmployeeOffer offer =
        offers
            .findByEmployeeId(employeeId)
            .filter(o -> o.getStorageKey() != null)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No accepted offer letter"));
    audit.record(
        AuditActor.from(actor), "OFFER_VIEWED", "EmployeeOffer", offer.getId(),
        Map.of("employeeId", employeeId), ip);
    return new PresignedView(storage.presignedGetUrl(offer.getStorageKey(), VIEW_TTL_SECONDS), VIEW_TTL_SECONDS);
  }

  // --- internals ------------------------------------------------------------

  /** The company/employee/terms text tokens shared by the read view + the PDF (no signature/date here). */
  private Map<String, String> readTokens(Employee employee, EmployeeOffer offer) {
    Map<String, Object> terms = offer.getTerms() == null ? Map.of() : offer.getTerms();
    Map<String, String> t = new LinkedHashMap<>();
    String company = companyName(employee);
    t.put("COMPANY_NAME", company);
    t.put("EMPLOYEE_NAME", nn(employee.getFullName()));
    t.put("DESIGNATION", nn(employee.getDesignation()));
    t.put("DATE", formatDate(str(terms.get("offerDate")), OFFER_DATE));
    t.put("JOINING_DATE", formatDate(str(terms.get("joiningDate")), APPOINTMENT_DATE));
    t.put("SALARY", str(terms.get("salary")));
    t.put("LOCATION", str(terms.get("location")));
    t.put("ACCEPT_NAME", nn(employee.getFullName()));
    t.put("ACCEPT_DATE", ""); // blank on the read view; the PDF overrides with today
    return t;
  }

  private Map<String, Object> pdfModel(String bodyHtml) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("bodyHtml", bodyHtml);
    m.put("footerText", "Private & Confidential");
    return m;
  }

  private String downloadUrl(EmployeeOffer offer) {
    return offer.getStorageKey() == null
        ? null
        : storage.presignedGetUrl(offer.getStorageKey(), VIEW_TTL_SECONDS);
  }

  private String formatOfferDate(EmployeeOffer offer) {
    Map<String, Object> terms = offer.getTerms() == null ? Map.of() : offer.getTerms();
    return formatDate(str(terms.get("offerDate")), OFFER_DATE);
  }

  private String companyName(Employee employee) {
    return companies.findById(employee.getCompanyId()).map(Company::getName).orElse("Company");
  }

  private String validateSignature(String dataUrl) {
    if (dataUrl == null || dataUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A signature is required to accept");
    }
    int comma = dataUrl.indexOf(',');
    if (!dataUrl.startsWith("data:image/") || comma < 0 || !dataUrl.substring(0, comma).contains("base64")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid signature image");
    }
    try {
      byte[] bytes = Base64.getDecoder().decode(dataUrl.substring(comma + 1));
      if (bytes.length == 0 || bytes.length > OnboardingDtos.MAX_UPLOAD_BYTES) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Signature image is empty or too large");
      }
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read the signature image");
    }
    return dataUrl;
  }

  /** Format an ISO date (yyyy-MM-dd) with the given pattern; fall back to the raw value if not a date. */
  private static String formatDate(String iso, DateTimeFormatter out) {
    if (iso == null || iso.isBlank()) {
      return "";
    }
    try {
      return LocalDate.parse(iso.substring(0, 10)).format(out);
    } catch (RuntimeException e) {
      return iso;
    }
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }

  private static String nn(String s) {
    return s == null ? "" : s;
  }

  private static String iso(java.time.Instant i) {
    return i == null ? null : i.toString();
  }
}
