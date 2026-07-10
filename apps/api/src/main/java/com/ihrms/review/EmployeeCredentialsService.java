package com.ihrms.review;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.mail.MailAddresses;
import com.ihrms.review.dto.ReviewDtos.AssignCredentialsRequest;
import com.ihrms.review.dto.ReviewDtos.AssignCredentialsResult;
import com.ihrms.support.TempPasswords;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * HR assigns internal credentials (mailbox address + password) to an APPROVED employee they onboarded
 * (§8, Stage 5). The address {@code localPart@companyDomain} IS a login email; the password is either
 * typed by HR or generated (generate is the default) and BCrypt-hashed. The credentials are emailed to
 * the employee's PERSONAL address (dev-logged); the plaintext is echoed to HR ONCE (dev only) and never
 * stored or returned again. Re-issue regenerates + re-emails. Audited {@code EMPLOYEE_CREDENTIALS_ASSIGNED}.
 */
@Service
public class EmployeeCredentialsService {

  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final AuthorizationService authz;
  private final AccountEmails accountEmails;
  private final MailAddresses mailAddresses;
  private final PasswordEncoder encoder;
  private final MailService mail;
  private final AuditService audit;
  private final AppProperties props;
  private final Environment env;

  public EmployeeCredentialsService(
      EmployeeRepository employees,
      CompanyRepository companies,
      AuthorizationService authz,
      AccountEmails accountEmails,
      MailAddresses mailAddresses,
      PasswordEncoder encoder,
      MailService mail,
      AuditService audit,
      AppProperties props,
      Environment env) {
    this.employees = employees;
    this.companies = companies;
    this.authz = authz;
    this.accountEmails = accountEmails;
    this.mailAddresses = mailAddresses;
    this.encoder = encoder;
    this.mail = mail;
    this.audit = audit;
    this.props = props;
    this.env = env;
  }

  @Transactional
  public AssignCredentialsResult assign(
      IhrmsPrincipal.User actor, String employeeId, AssignCredentialsRequest input, String ip) {
    // HR access gate (§6): only the acting HR's OWN onboarded employee, same company.
    authz.assertCanAccessEmployee(actor, employeeId);
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

    // Credentials only after Manager approval — the employee ID must already be minted (§5).
    if (employee.getStatus() != EmployeeStatus.APPROVED) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Credentials can be assigned only after the employee is approved");
    }

    Company company =
        companies
            .findById(employee.getCompanyId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

    var address = mailAddresses.resolve(input.localPart(), company.getMailDomain());
    // Unique across every login identifier; a re-issue keeping the same address is not a clash (self).
    accountEmails.assertMailAddressAvailable(address.email(), employee.getId());

    boolean reissue = employee.getMailAddress() != null; // captured before we overwrite it
    String password =
        input.password() != null && !input.password().isBlank()
            ? input.password()
            : TempPasswords.generate();

    employee.setMailLocalPart(address.localPart());
    employee.setMailAddress(address.email());
    employee.setPasswordHash(encoder.encode(password));
    employee.setCredentialsAssignedAt(Instant.now());
    try {
      employees.save(employee);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "That mailbox address is already in use");
    }

    String loginUrl = props.webAppUrl() + "/login";
    mail.sendEmployeeCredentials(employee.getEmail(), address.email(), password, loginUrl);
    audit.record(
        AuditActor.from(actor),
        "EMPLOYEE_CREDENTIALS_ASSIGNED",
        "Employee",
        employee.getId(),
        Map.of("mailAddress", address.email(), "reissued", reissue),
        ip);

    return new AssignCredentialsResult(
        employee.getId(),
        address.email(),
        employee.getEmail(),
        employee.getCredentialsAssignedAt().toString(),
        isProd() ? null : password);
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }
}
