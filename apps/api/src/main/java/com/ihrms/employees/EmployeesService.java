package com.ihrms.employees;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.employees.dto.EmployeeDtos.EmployeePage;
import com.ihrms.employees.dto.EmployeeDtos.EmployeeSummaryView;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
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
  private final AuditService audit;
  private final MailService mail;
  private final AccountEmails accountEmails;
  private final AppProperties props;

  public EmployeesService(
      EmployeeRepository employees,
      CompanyRepository companies,
      AuditService audit,
      MailService mail,
      AccountEmails accountEmails,
      AppProperties props) {
    this.employees = employees;
    this.companies = companies;
    this.audit = audit;
    this.mail = mail;
    this.accountEmails = accountEmails;
    this.props = props;
  }

  public OnboardEmployeeResult onboard(
      OnboardEmployeeRequest input, IhrmsPrincipal.User actor, String ip) {
    String companyId = companyOf(actor);
    Company company =
        companies
            .findById(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No company in scope"));

    String email = input.email().trim().toLowerCase();
    accountEmails.assertAvailableForEmployee(email); // unique across staff + employees (§6)
    String fullName = input.fullName().trim();
    String designation = input.designation().trim();

    Employee employee = new Employee();
    employee.setFullName(fullName);
    employee.setEmail(email);
    employee.setDesignation(designation);
    employee.setDateOfJoining(input.dateOfJoining());
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(actor.userId());
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

    audit.record(
        AuditActor.from(actor),
        "EMPLOYEE_ONBOARDED",
        "Employee",
        employee.getId(),
        Map.of("email", email, "fullName", fullName, "designation", designation),
        ip);

    return new OnboardEmployeeResult(summary(employee), loginUrl);
  }

  /**
   * The HR's onboarding queue: their onboarded employees within their company (§6), with optional
   * name/email search and status filter, paginated. This is how HR reaches in-flight employees —
   * they have no employee ID yet (allocated on approval, §5).
   */
  public EmployeePage queue(
      IhrmsPrincipal.User actor, String search, EmployeeStatus status, Pageable pageable) {
    String companyId = companyOf(actor);
    Specification<Employee> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("companyId"), companyId));
          p.add(cb.equal(root.get("onboardingHrId"), actor.userId())); // own onboarded only (§6)
          if (status != null) {
            p.add(cb.equal(root.get("status"), status));
          }
          if (search != null && !search.isBlank()) {
            String like = "%" + search.trim().toLowerCase() + "%";
            p.add(
                cb.or(
                    cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(cb.lower(root.get("email")), like)));
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
