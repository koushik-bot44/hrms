package com.ihrms.accountant;

import com.ihrms.accountant.dto.AccountantDtos.AccountantStatus;
import com.ihrms.accountant.dto.AccountantDtos.AccountantView;
import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeePage;
import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeeRow;
import com.ihrms.accountant.dto.AccountantDtos.ProvisionAccountantRequest;
import com.ihrms.accountant.dto.AccountantDtos.ProvisionAccountantResult;
import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.review.EmployeeRecordAssembler;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The Accountant (ARCHITECTURE.md §2/§6): one cross-company, READ-ONLY staff account. This service
 * provisions the singleton (SUPER_ADMIN-only) and serves the Accountant's reads — the list of APPROVED
 * employees across all companies and each one's full record via the shared {@link EmployeeRecordAssembler}
 * (masked by default; the reveal is an explicit, audited action). No method mutates onboarding data.
 */
@Service
public class AccountantService {

  private final UserRepository users;
  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final EmployeeRecordAssembler assembler;
  private final AccountEmails accountEmails;
  private final PasswordEncoder encoder;
  private final MailService mail;
  private final AuditService audit;
  private final Environment env;

  public AccountantService(
      UserRepository users,
      EmployeeRepository employees,
      CompanyRepository companies,
      EmployeeRecordAssembler assembler,
      AccountEmails accountEmails,
      PasswordEncoder encoder,
      MailService mail,
      AuditService audit,
      Environment env) {
    this.users = users;
    this.employees = employees;
    this.companies = companies;
    this.assembler = assembler;
    this.accountEmails = accountEmails;
    this.encoder = encoder;
    this.mail = mail;
    this.audit = audit;
    this.env = env;
  }

  // --- Provisioning (SUPER_ADMIN, singleton) --------------------------------

  /** Whether the single Accountant has been provisioned — drives the SUPER_ADMIN UI. */
  public AccountantStatus status() {
    return users
        .findFirstByRoleOrderByCreatedAtAsc(UserRole.ACCOUNTANT)
        .map(u -> new AccountantStatus(true, view(u)))
        .orElseGet(() -> new AccountantStatus(false, null));
  }

  /** Create THE Accountant. Rejects a second one (singleton) and a taken email. Audited. */
  public ProvisionAccountantResult provision(
      IhrmsPrincipal.User actor, ProvisionAccountantRequest input, String ip) {
    if (users.existsByRole(UserRole.ACCOUNTANT)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An Accountant already exists (only one is allowed)");
    }
    String email = input.email().trim().toLowerCase();
    accountEmails.assertAvailableForStaff(email); // unique across staff + employees (§6)

    User user = new User();
    user.setEmail(email);
    user.setName(input.name().trim());
    user.setRole(UserRole.ACCOUNTANT);
    user.setCompanyId(null); // cross-company, like SUPER_ADMIN
    user.setPasswordHash(encoder.encode(input.password()));
    user.setStatus("ACTIVE");
    try {
      users.save(user);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Email \"" + email + "\" is already in use");
    }

    mail.sendStaffInvite(email, "ACCOUNTANT", input.password());
    // Portal-level event (no companyId) — the Accountant belongs to no company.
    audit.record(
        new AuditActor("USER", actor.userId(), null),
        "ACCOUNTANT_PROVISIONED",
        "User",
        user.getId(),
        Map.of("email", email),
        ip);

    return new ProvisionAccountantResult(view(user), isProd() ? null : input.password());
  }

  // --- Reads (ACCOUNTANT: cross-company, APPROVED-only) ---------------------

  /** APPROVED employees across ALL companies, optional company filter + name/email/ID search. */
  public ApprovedEmployeePage listApproved(String search, String companyId, Pageable pageable) {
    Specification<Employee> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("status"), EmployeeStatus.APPROVED)); // approved only (§2)
          if (isPresent(companyId)) {
            p.add(cb.equal(root.get("companyId"), companyId));
          }
          if (isPresent(search)) {
            String like = "%" + search.trim().toLowerCase() + "%";
            p.add(
                cb.or(
                    cb.like(cb.lower(root.get("fullName")), like),
                    cb.like(cb.lower(root.get("email")), like),
                    cb.like(cb.lower(root.get("employeeCode")), like)));
          }
          return cb.and(p.toArray(new Predicate[0]));
        };
    Page<Employee> page = employees.findAll(spec, pageable);
    Map<String, String> companyNames = companyNames(page.getContent());
    return new ApprovedEmployeePage(
        page.getContent().stream().map(e -> row(e, companyNames)).toList(),
        page.getNumber(),
        page.getSize(),
        page.getTotalElements(),
        page.getTotalPages());
  }

  /** The full record of an APPROVED employee (masked); the read is audited under their company. */
  public EmployeeRecordView record(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadApproved(employeeId);
    audit.record(
        new AuditActor("USER", actor.userId(), employee.getCompanyId()),
        "EMPLOYEE_RECORD_VIEWED",
        "Employee",
        employee.getId(),
        Map.of("email", employee.getEmail()),
        ip);
    return assembler.build(employee);
  }

  /** Reveal the masked sensitive values — an explicit, audited action (§6), reusing HR's mechanism. */
  public RevealedSensitive reveal(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadApproved(employeeId);
    audit.record(
        new AuditActor("USER", actor.userId(), employee.getCompanyId()),
        "SENSITIVE_FIELD_REVEALED",
        "Employee",
        employee.getId(),
        Map.of("email", employee.getEmail()),
        ip);
    return assembler.reveal(employee);
  }

  // --- internals ------------------------------------------------------------

  /** Only APPROVED employees are visible to the Accountant; others read as not-found (§2). */
  private Employee loadApproved(String employeeId) {
    Employee employee =
        employees
            .findById(employeeId)
            .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    return employee;
  }

  private ApprovedEmployeeRow row(Employee e, Map<String, String> companyNames) {
    return new ApprovedEmployeeRow(
        e.getId(),
        e.getEmployeeCode(),
        e.getFullName(),
        e.getEmail(),
        e.getCompanyId(),
        e.getCompanyId() == null ? null : companyNames.get(e.getCompanyId()),
        e.getDesignation(),
        e.getDateOfJoining() == null ? null : e.getDateOfJoining().toString(),
        // Approval is the last lifecycle mutation, so updatedAt ~ the approved date.
        e.getUpdatedAt() == null ? null : e.getUpdatedAt().toString());
  }

  private Map<String, String> companyNames(List<Employee> emps) {
    Set<String> ids =
        emps.stream()
            .map(Employee::getCompanyId)
            .filter(id -> id != null)
            .collect(Collectors.toSet());
    return ids.isEmpty()
        ? Map.of()
        : companies.findAllById(ids).stream()
            .collect(Collectors.toMap(Company::getId, Company::getName));
  }

  private AccountantView view(User u) {
    return new AccountantView(
        u.getId(), u.getEmail(), u.getName(), u.getStatus(),
        u.getCreatedAt() == null ? null : u.getCreatedAt().toString());
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }

  private static boolean isPresent(String s) {
    return s != null && !s.isBlank();
  }
}
