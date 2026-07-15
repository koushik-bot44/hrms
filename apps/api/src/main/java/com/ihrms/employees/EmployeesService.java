package com.ihrms.employees;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.employees.dto.EmployeeDtos.EmployeePage;
import com.ihrms.employees.dto.EmployeeDtos.EmployeeSummaryView;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import com.ihrms.employees.dto.EmployeeDtos.SuperAdminOnboardRequest;
import java.time.LocalDate;
import jakarta.persistence.criteria.Predicate;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * Employee onboarding (ARCHITECTURE.md §3.2). HR-only (URL rule + @PreAuthorize); scoped to the HR's
 * company. HR provides full name, email, designation and date of joining; the record is created with
 * {@code status = INVITED} and <strong>no employee ID</strong> — the unique ID is allocated only on
 * Manager approval (§5). A selection email + employee-login link is sent and the action is audited.
 */
@Service
public class EmployeesService {

  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final AuditService audit;
  private final MailService mail;
  private final AccountEmails accountEmails;
  private final AppProperties props;

  public EmployeesService(
      EmployeeRepository employees,
      CompanyRepository companies,
      TeamRepository teams,
      AuditService audit,
      MailService mail,
      AccountEmails accountEmails,
      AppProperties props) {
    this.employees = employees;
    this.companies = companies;
    this.teams = teams;
    this.audit = audit;
    this.mail = mail;
    this.accountEmails = accountEmails;
    this.props = props;
  }

  /** HR onboards into their own company, attaching the employee to themselves as the onboarding HR. */
  public OnboardEmployeeResult onboard(
      OnboardEmployeeRequest input, IhrmsPrincipal.User actor, String ip) {
    return createAndInvite(
        companyOf(actor),
        actor.userId(),
        input.fullName(),
        input.email(),
        input.designation(),
        input.dateOfJoining(),
        actor,
        ip);
  }

  /**
   * SUPER_ADMIN onboards into a chosen company by selecting a team (§2): the employee attaches to that
   * team's HR exactly as if the HR had onboarded them. The team must belong to the given company and
   * have an HR assigned; everything downstream (verification by that HR, approval by that team's
   * Manager) is unchanged.
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
    return createAndInvite(
        companyId,
        team.getHrUserId(),
        input.fullName(),
        input.email(),
        input.designation(),
        input.dateOfJoining(),
        actor,
        ip);
  }

  /** Shared onboarding: create the INVITED employee, send the selection email, audit. */
  private OnboardEmployeeResult createAndInvite(
      String companyId,
      String onboardingHrId,
      String rawFullName,
      String rawEmail,
      String rawDesignation,
      LocalDate dateOfJoining,
      IhrmsPrincipal.User actor,
      String ip) {
    Company company =
        companies
            .findById(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

    String email = rawEmail.trim().toLowerCase();
    accountEmails.assertAvailableForEmployee(email); // unique across staff + employees (§6)
    String fullName = rawFullName.trim();
    String designation = rawDesignation.trim();

    Employee employee = new Employee();
    employee.setFullName(fullName);
    employee.setEmail(email);
    employee.setDesignation(designation);
    employee.setDateOfJoining(dateOfJoining);
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(onboardingHrId);
    employee.setStatus(EmployeeStatus.INVITED);
    try {
      // Flush inside the try so the global-unique-email violation surfaces here as a 409.
      employees.saveAndFlush(employee);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An employee with this email already exists");
    }

    String loginUrl =
        props.webAppUrl().replaceAll("/+$", "")
            + "/employee/login?email="
            + URLEncoder.encode(email, StandardCharsets.UTF_8);
    mail.sendEmployeeSelection(email, fullName, designation, company.getName(), loginUrl);

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
   * The HR's onboarding queue: their onboarded employees within their company (§6), with optional
   * name / email / employee-ID search and status filter, paginated. This is how HR reaches in-flight employees —
   * they have no employee ID yet (allocated on approval, §5).
   */
  public EmployeePage queue(
      IhrmsPrincipal.User actor, String search, EmployeeStatus status, Pageable pageable) {
    String companyId = companyOf(actor);
    Specification<Employee> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("companyId"), companyId));
          // HR sees only their own onboarded employees (§6); a COMPANY_ADMIN sees the whole company.
          // No other role reaches this method (URL rule + @PreAuthorize gate it to HR / COMPANY_ADMIN).
          if (actor.role() == UserRole.HR) {
            p.add(cb.equal(root.get("onboardingHrId"), actor.userId()));
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
    Page<Employee> page = employees.findAll(spec, pageable);
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
