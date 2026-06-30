package com.ihrms.employees;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.support.EmployeeCodeService;
import com.ihrms.domain.support.EmployeeCodes;
import com.ihrms.employees.dto.EmployeeDtos.EmployeeSummaryView;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Employee onboarding (ARCHITECTURE.md §3.2/§5). HR-only (URL rule + @PreAuthorize); scoped to
 * the HR's company. The employee ID is allocated from an atomic per-company sequence so concurrent
 * onboards never collide; the new record + login link are emailed and the action is audited.
 */
@Service
public class EmployeesService {

  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final EmployeeCodeService codes;
  private final AuditService audit;
  private final MailService mail;
  private final AppProperties props;

  public EmployeesService(
      EmployeeRepository employees,
      CompanyRepository companies,
      EmployeeCodeService codes,
      AuditService audit,
      MailService mail,
      AppProperties props) {
    this.employees = employees;
    this.companies = companies;
    this.codes = codes;
    this.audit = audit;
    this.mail = mail;
    this.props = props;
  }

  public OnboardEmployeeResult onboard(
      OnboardEmployeeRequest input, IhrmsPrincipal.User actor, String ip) {
    String companyId = companyOf(actor);
    Company company =
        companies
            .findById(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "No company in scope"));

    int sequence = codes.allocateSequence(companyId);
    if (sequence > EmployeeCodes.SEQ_MAX) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Employee ID sequence exhausted for this company");
    }
    String employeeCode = EmployeeCodes.format(company.getCode(), sequence);
    String email = input.email().trim().toLowerCase();

    Employee employee = new Employee();
    employee.setEmployeeCode(employeeCode);
    employee.setEmail(email);
    employee.setCompanyId(companyId);
    employee.setOnboardingHrId(actor.userId());
    employee.setStatus(EmployeeStatus.INVITED);
    employees.save(employee);

    String loginUrl = props.webAppUrl().replaceAll("/+$", "") + "/login";
    mail.sendEmployeeOnboarding(email, employeeCode, loginUrl);

    audit.record(
        AuditActor.from(actor),
        "EMPLOYEE_ONBOARDED",
        "Employee",
        employee.getId(),
        Map.of("employeeCode", employeeCode, "email", email),
        ip);

    return new OnboardEmployeeResult(summary(employee), loginUrl);
  }

  /** Employees this HR onboarded, within their company (§6). */
  public List<EmployeeSummaryView> listMine(IhrmsPrincipal.User actor) {
    String companyId = companyOf(actor);
    return employees
        .findByCompanyIdAndOnboardingHrIdOrderByCreatedAtDesc(companyId, actor.userId())
        .stream()
        .map(EmployeesService::summary)
        .toList();
  }

  private static EmployeeSummaryView summary(Employee e) {
    return new EmployeeSummaryView(
        e.getId(), e.getEmployeeCode(), e.getEmail(), e.getStatus(), e.getCreatedAt().toString());
  }

  private String companyOf(IhrmsPrincipal.User actor) {
    if (actor.companyId() == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No company in scope");
    }
    return actor.companyId();
  }
}
