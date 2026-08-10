package com.ihrms.employees;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Form2Info;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.Form2InfoRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.employees.dto.EmployeeDtos.EmployeePage;
import com.ihrms.employees.dto.EmployeeDtos.EmployeeSummaryView;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import com.ihrms.employees.dto.EmployeeDtos.SuperAdminOnboardRequest;
import com.ihrms.onboarding.FormMappers;
import com.ihrms.onboarding.OfferService;
import com.ihrms.onboarding.dto.OfferDtos.OfferTermsRequest;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2Request;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import jakarta.persistence.criteria.Predicate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Employee onboarding (ARCHITECTURE.md §3.2). HR (own company) or SUPER_ADMIN (cross-company) FILL
 * FORM 2 — Employee Info; submitting it creates the record ({@code status = INVITED}, <strong>no
 * employee ID</strong> — allocated only on Manager approval, §5) and sends the selection email + login
 * link to the PERSONAL email (the login identity). Form 2 stays editable while INVITED (locked once the
 * employee starts, 409); a personal-email change re-invites. Everything is audited.
 */
@Service
public class EmployeesService {

  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final Form2InfoRepository form2s;
  private final AuditService audit;
  private final MailService mail;
  private final AccountEmails accountEmails;
  private final AuthorizationService authz;
  private final OfferService offers;
  private final com.ihrms.web.WebLinks links;

  public EmployeesService(
      EmployeeRepository employees,
      CompanyRepository companies,
      TeamRepository teams,
      Form2InfoRepository form2s,
      AuditService audit,
      MailService mail,
      AccountEmails accountEmails,
      AuthorizationService authz,
      OfferService offers,
      com.ihrms.web.WebLinks links) {
    this.employees = employees;
    this.companies = companies;
    this.teams = teams;
    this.form2s = form2s;
    this.audit = audit;
    this.mail = mail;
    this.accountEmails = accountEmails;
    this.authz = authz;
    this.offers = offers;
    this.links = links;
  }

  /**
   * HR onboards into their own company by FILLING FORM 2 (§3.2) — the employee attaches to the HR as
   * the onboarding HR. Submitting Form 2 creates the INVITED record and sends the invite in one action.
   */
  public OnboardEmployeeResult onboard(
      OnboardEmployeeRequest input, IhrmsPrincipal.User actor, String ip) {
    return createAndInvite(companyOf(actor), actor.userId(), input.form2(), input.offer(), actor, ip);
  }

  /**
   * SUPER_ADMIN onboards into a chosen company by selecting a team (§2) and filling Form 2: the employee
   * attaches to that team's HR exactly as if the HR had onboarded them. The team must belong to the
   * given company and have an HR assigned; everything downstream (verification by that HR, approval by
   * that team's Manager) is unchanged.
   */
  public OnboardEmployeeResult onboardForCompany(
      String companyId, SuperAdminOnboardRequest input, IhrmsPrincipal.User actor, String ip) {
    Team team =
        teams
            .findByIdAndCompanyId(input.teamId(), companyId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "That team does not belong to the selected company"));
    if (team.getHrUserId() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "The selected team has no HR assigned yet");
    }
    return createAndInvite(companyId, team.getHrUserId(), input.form2(), input.offer(), actor, ip);
  }

  /**
   * Shared onboarding (§3.2): create the INVITED employee from the HR/SA-authored Form 2, persist that
   * Form 2, send the selection email to the PERSONAL email (the login identity), and audit. The employee
   * ID stays blank until Manager approval mints it; Spark ID + official email are left inert.
   */
  private OnboardEmployeeResult createAndInvite(
      String companyId,
      String onboardingHrId,
      Form2Request form2,
      OfferTermsRequest offer,
      IhrmsPrincipal.User actor,
      String ip) {
    Company company =
        companies
            .findById(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

    String email = norm(form2.personalEmail());
    accountEmails.assertAvailableForEmployee(email); // unique across staff + employees (§6)
    String fullName = form2.fullName().trim();
    String designation = form2.designation().trim();
    LocalDate dateOfJoining = parseDateOfJoining(form2.dateOfJoining());

    Employee employee = new Employee();
    employee.setFullName(fullName);
    employee.setEmail(email);
    employee.setDesignation(designation);
    employee.setDateOfJoining(dateOfJoining);
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(onboardingHrId);
    employee.setStatus(EmployeeStatus.INVITED);
    employee.setItrRequired(true); // NEW onboardings must upload an ITR (§3.2); existing rows stay false.
    try {
      // Flush inside the try so the global-unique-email violation surfaces here as a 409.
      employees.saveAndFlush(employee);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An employee with this email already exists");
    }

    // Persist the HR/SA-authored Form 2 (§3.2). The minted code reaches Form 2 at render time (null now).
    Form2Info f2 = new Form2Info();
    f2.setEmployeeId(employee.getId());
    f2.setData(FormMappers.toForm2Data(form2, null));
    form2s.save(f2);

    // The Offer Letter opens onboarding (§3.2): a SENT offer the invited employee must accept before any
    // form unlocks. Terms reuse the Form-2 joining date + designation + name; only salary/location are new.
    offers.createOffer(employee, offer, actor, ip);

    String loginUrl = loginUrl(email, company.getSlug());
    mail.sendEmployeeSelection(email, fullName, designation, company.getName(), loginUrl, companyId);

    // Partition the audit under the TARGET company (a SUPER_ADMIN actor has no company of its own).
    audit.record(
        new AuditActor("USER", actor.userId(), companyId),
        "EMPLOYEE_ONBOARDED",
        "Employee",
        employee.getId(),
        Map.of("email", email, "fullName", fullName, "designation", designation),
        ip);

    return new OnboardEmployeeResult(summary(employee), loginUrl);
  }

  /**
   * HR/SA edits Form 2 (§3.2) — allowed ONLY while the employee is {@code INVITED} (a manual edit once
   * they start is rejected, 409). Keeps the Employee columns in sync with Form 2. A change to the
   * PERSONAL EMAIL (the login identity) re-sends the invite to the new address and re-checks global
   * uniqueness; editing other fields does not re-invite. HR is scoped to their own onboarded employees;
   * SUPER_ADMIN may edit any (both via {@link AuthorizationService}).
   */
  public Form2View editForm2(
      String employeeId, Form2Request form2, IhrmsPrincipal.User actor, String ip) {
    Employee employee = loadAccessible(actor, employeeId);
    if (employee.getStatus() != EmployeeStatus.INVITED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Form 2 is locked once the employee starts onboarding");
    }
    Company company =
        companies
            .findById(employee.getCompanyId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

    LocalDate dateOfJoining = parseDateOfJoining(form2.dateOfJoining());
    String newEmail = norm(form2.personalEmail());
    String oldEmail = employee.getEmail();
    boolean emailChanged = !newEmail.equals(oldEmail);
    if (emailChanged) {
      accountEmails.assertAvailableForEmployee(newEmail); // staff/mailbox collisions
    }

    // Keep the Employee columns in lockstep with Form 2 (the login identity + summary fields).
    employee.setFullName(form2.fullName().trim());
    employee.setEmail(newEmail);
    employee.setDesignation(form2.designation().trim());
    employee.setDateOfJoining(dateOfJoining);
    try {
      employees.saveAndFlush(employee); // surfaces an employee↔employee email clash as a 409
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An employee with this email already exists");
    }

    Form2Info f2 =
        form2s
            .findByEmployeeId(employeeId)
            .orElseGet(
                () -> {
                  Form2Info created = new Form2Info();
                  created.setEmployeeId(employeeId);
                  return created;
                });
    f2.setData(FormMappers.toForm2Data(form2, f2.getData()));
    form2s.save(f2);

    if (emailChanged) {
      // The personal email IS the login identity — re-invite the new address; the old gets nothing.
      mail.sendEmployeeSelection(
          newEmail, employee.getFullName(), employee.getDesignation(), company.getName(),
          loginUrl(newEmail, company.getSlug()), employee.getCompanyId());
      audit.record(
          new AuditActor("USER", actor.userId(), employee.getCompanyId()),
          "EMPLOYEE_REINVITED",
          "Employee",
          employeeId,
          Map.of("oldEmail", oldEmail, "newEmail", newEmail),
          ip);
    }
    audit.record(
        new AuditActor("USER", actor.userId(), employee.getCompanyId()),
        "FORM2_UPDATED",
        "Form2Info",
        f2.getId(),
        Map.of("employeeId", employeeId, "reinvited", emailChanged),
        ip);

    return FormMappers.form2View(f2, employee.getEmployeeCode());
  }

  /** Load an employee the actor may act on (HR own-onboarded / SUPER_ADMIN any); 404 hides existence. */
  private Employee loadAccessible(IhrmsPrincipal.User actor, String employeeId) {
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
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found");
    }
    return employee;
  }

  /** The slugged employee-login link for the invite email: {WEB_APP_URL}/{slug}/employee/login?email=… */
  private String loginUrl(String email, String slug) {
    return links.emailLinkForSlug(
        slug, "/employee/login?email=" + URLEncoder.encode(email, StandardCharsets.UTF_8));
  }

  private static String norm(String email) {
    return email == null ? null : email.trim().toLowerCase();
  }

  /** Form 2's date-of-joining is a lenient string; onboarding/edit require a real date. */
  private static LocalDate parseDateOfJoining(String raw) {
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Date of joining is required");
    }
    try {
      return LocalDate.parse(raw.trim());
    } catch (DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use YYYY-MM-DD for date of joining");
    }
  }

  /**
   * The HR's onboarding queue: their onboarded employees within their company (§6), with optional
   * name / email / employee-ID search and status filter, paginated. This is how HR reaches in-flight employees —
   * they have no employee ID yet (allocated on approval, §5).
   */
  public EmployeePage queue(
      IhrmsPrincipal.User actor, String search, EmployeeStatus status, Pageable pageable) {
    String companyId = companyOf(actor);
    // HR sees only their own onboarded employees (§6); a COMPANY_ADMIN sees the whole company. No other
    // role reaches this method (URL rule + @PreAuthorize gate it to HR / COMPANY_ADMIN).
    String hrFilter = actor.role() == UserRole.HR ? actor.userId() : null;
    return toPage(employees.findAll(employeeSpec(companyId, hrFilter, search, status), pageable));
  }

  /**
   * Company-wide employee list for a SUPER_ADMIN browsing a chosen company (§2) — no HR filter, all
   * teams. Reached via {@code GET /companies/{companyId}/employees} (SUPER_ADMIN-only URL rule).
   */
  public EmployeePage queueForCompany(
      String companyId, String search, EmployeeStatus status, Pageable pageable) {
    companies
        .findById(companyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));
    return toPage(employees.findAll(employeeSpec(companyId, null, search, status), pageable));
  }

  /** Company-scoped employee query; {@code onboardingHrId} narrows to one HR's queue when non-null. */
  private static Specification<Employee> employeeSpec(
      String companyId, String onboardingHrId, String search, EmployeeStatus status) {
    return (root, q, cb) -> {
      List<Predicate> p = new ArrayList<>();
      p.add(cb.equal(root.get("companyId"), companyId));
      if (onboardingHrId != null) {
        p.add(cb.equal(root.get("onboardingHrId"), onboardingHrId));
      }
      if (status != null) {
        p.add(cb.equal(root.get("status"), status));
      }
      if (search != null && !search.isBlank()) {
        String like = "%" + search.trim().toLowerCase() + "%";
        // employeeCode is null until approval — a LIKE on null yields no match (never an NPE).
        p.add(
            cb.or(
                cb.like(cb.lower(root.get("fullName")), like),
                cb.like(cb.lower(root.get("email")), like),
                cb.like(cb.lower(root.get("employeeCode")), like)));
      }
      return cb.and(p.toArray(new Predicate[0]));
    };
  }

  private static EmployeePage toPage(Page<Employee> page) {
    return new EmployeePage(
        page.getContent().stream().map(EmployeesService::summary).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }

  private static EmployeeSummaryView summary(Employee e) {
    return new EmployeeSummaryView(
        e.getId(),
        e.getEmployeeCode(),
        e.getFullName(),
        e.getEmail(),
        e.getDesignation(),
        e.getDateOfJoining() == null ? null : e.getDateOfJoining().toString(),
        e.getStatus(),
        e.getCreatedAt().toString());
  }

  private String companyOf(IhrmsPrincipal.User actor) {
    if (actor.companyId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No company in scope");
    }
    return actor.companyId();
  }
}
