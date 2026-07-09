package com.ihrms.accountant;

import com.ihrms.accountant.dto.AccountantDtos.AccountantStatus;
import com.ihrms.accountant.dto.AccountantDtos.AccountantView;
import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeePage;
import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeeRow;
import com.ihrms.accountant.dto.AccountantDtos.ProvisionAccountantRequest;
import com.ihrms.accountant.dto.AccountantDtos.ProvisionAccountantResult;
import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditQueryService;
import com.ihrms.audit.AuditService;
import com.ihrms.audit.dto.AuditDtos.AuditPage;
import com.ihrms.auth.AccountEmails;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.MailService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.review.EmployeeRecordAssembler;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * The read-only viewer service (ARCHITECTURE.md §2/§6) shared by two roles, scoped by the caller:
 * <ul>
 *   <li><b>ACCOUNTS_ADMIN</b> — cross-company SINGLETON (provisioned by SUPER_ADMIN); sees APPROVED
 *       employees across every company.</li>
 *   <li><b>ACCOUNTANT</b> — team-scoped; sees only the APPROVED employees onboarded by its team's HR.</li>
 * </ul>
 * Records come from the shared {@link EmployeeRecordAssembler} (masked; audited reveal). Nothing here
 * mutates onboarding data.
 */
@Service
public class AccountantService {

  private final UserRepository users;
  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final EmployeeRecordAssembler assembler;
  private final AuditQueryService auditQuery;
  private final AccountEmails accountEmails;
  private final com.ihrms.mail.MailAddresses mailAddresses;
  private final PasswordEncoder encoder;
  private final MailService mail;
  private final AuditService audit;
  private final Environment env;

  public AccountantService(
      UserRepository users,
      EmployeeRepository employees,
      CompanyRepository companies,
      TeamRepository teams,
      EmployeeRecordAssembler assembler,
      AuditQueryService auditQuery,
      AccountEmails accountEmails,
      com.ihrms.mail.MailAddresses mailAddresses,
      PasswordEncoder encoder,
      MailService mail,
      AuditService audit,
      Environment env) {
    this.users = users;
    this.employees = employees;
    this.companies = companies;
    this.teams = teams;
    this.assembler = assembler;
    this.auditQuery = auditQuery;
    this.accountEmails = accountEmails;
    this.mailAddresses = mailAddresses;
    this.encoder = encoder;
    this.mail = mail;
    this.audit = audit;
    this.env = env;
  }

  // --- Provisioning: the cross-company ACCOUNTS_ADMIN singleton (SUPER_ADMIN) ----

  /** Whether the single Accounts Admin has been provisioned — drives the SUPER_ADMIN UI. */
  public AccountantStatus status() {
    return users
        .findFirstByRoleOrderByCreatedAtAsc(UserRole.ACCOUNTS_ADMIN)
        .map(u -> new AccountantStatus(true, view(u)))
        .orElseGet(() -> new AccountantStatus(false, null));
  }

  /** Create THE Accounts Admin. Rejects a second one (singleton) and a taken email. Audited. */
  public ProvisionAccountantResult provision(
      IhrmsPrincipal.User actor, ProvisionAccountantRequest input, String ip) {
    if (users.existsByRole(UserRole.ACCOUNTS_ADMIN)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "An Accounts Admin already exists (only one is allowed)");
    }
    // Platform-domain mailbox: localPart@ihrms, which IS the login email (§8).
    var address =
        mailAddresses.resolve(input.localPart(), com.ihrms.mail.MailAddresses.PLATFORM_DOMAIN);
    accountEmails.assertAvailableForStaff(address.email()); // unique across staff + employees (§6)

    User user = new User();
    user.setEmail(address.email());
    user.setMailLocalPart(address.localPart());
    user.setName(input.name().trim());
    user.setRole(UserRole.ACCOUNTS_ADMIN);
    user.setCompanyId(null); // cross-company, like SUPER_ADMIN
    user.setPasswordHash(encoder.encode(input.password()));
    user.setStatus("ACTIVE");
    try {
      users.save(user);
    } catch (DataIntegrityViolationException e) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Email \"" + address.email() + "\" is already in use");
    }

    mail.sendStaffInvite(address.email(), "ACCOUNTS_ADMIN", input.password());
    // Portal-level event (no companyId) — the Accounts Admin belongs to no company.
    audit.record(
        new AuditActor("USER", actor.userId(), null),
        "ACCOUNTS_ADMIN_PROVISIONED",
        "User",
        user.getId(),
        Map.of("email", address.email()),
        ip);

    return new ProvisionAccountantResult(view(user), isProd() ? null : input.password());
  }

  /**
   * Remove the current Accounts Admin so a new one can be provisioned (§2). Idempotent — a no-op if
   * none exists. Audited. Their audit history is retained (audit rows reference a plain actorId, not an
   * FK), and any already-issued access token simply expires.
   */
  public AccountantStatus remove(IhrmsPrincipal.User actor, String ip) {
    users
        .findFirstByRoleOrderByCreatedAtAsc(UserRole.ACCOUNTS_ADMIN)
        .ifPresent(
            admin -> {
              users.delete(admin);
              audit.record(
                  new AuditActor("USER", actor.userId(), null),
                  "ACCOUNTS_ADMIN_REMOVED",
                  "User",
                  admin.getId(),
                  Map.of("email", admin.getEmail()),
                  ip);
            });
    return new AccountantStatus(false, null);
  }

  // --- Reads (scoped by role: ACCOUNTS_ADMIN cross-company, ACCOUNTANT own-team) --

  /** APPROVED employees in scope, optional company filter (Accounts Admin only) + name/email/ID search. */
  public ApprovedEmployeePage listApproved(
      IhrmsPrincipal.User actor, String search, String companyId, Pageable pageable) {
    List<String> hrIds = hrScope(actor); // null = cross-company; else the team's HR ids
    Specification<Employee> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("status"), EmployeeStatus.APPROVED)); // approved only (§2)
          if (hrIds != null) {
            // Team scope: only employees onboarded by the accountant's team HR (empty -> none).
            p.add(hrIds.isEmpty() ? cb.disjunction() : root.get("onboardingHrId").in(hrIds));
          } else if (isPresent(companyId)) {
            p.add(cb.equal(root.get("companyId"), companyId)); // cross-company optional filter
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

  /** The full record of an in-scope APPROVED employee (masked); the read is audited. */
  public EmployeeRecordView record(IhrmsPrincipal.User actor, String employeeId, String ip) {
    Employee employee = loadApproved(actor, employeeId);
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
    Employee employee = loadApproved(actor, employeeId);
    audit.record(
        new AuditActor("USER", actor.userId(), employee.getCompanyId()),
        "SENSITIVE_FIELD_REVEALED",
        "Employee",
        employee.getId(),
        Map.of("email", employee.getEmail()),
        ip);
    return assembler.reveal(employee);
  }

  /** Approval-only audit, scoped: all companies (Accounts Admin) or the team's employees (Accountant). */
  public AuditPage approvalAudit(
      IhrmsPrincipal.User actor, String companyId, Instant from, Instant to, Pageable pageable) {
    List<String> hrIds = hrScope(actor);
    if (hrIds == null) {
      return auditQuery.approvalTrail(companyId, null, from, to, pageable); // cross-company
    }
    // Team scope: approval events targeting the team's employees (any status).
    List<String> employeeIds =
        hrIds.isEmpty()
            ? List.of()
            : employees.findByOnboardingHrIdIn(hrIds).stream().map(Employee::getId).toList();
    return auditQuery.approvalTrail(actor.companyId(), employeeIds, from, to, pageable);
  }

  // --- internals ------------------------------------------------------------

  /** {@code null} = cross-company (ACCOUNTS_ADMIN); otherwise the accountant's team HR ids (team scope). */
  private List<String> hrScope(IhrmsPrincipal.User actor) {
    if (actor.role() == UserRole.ACCOUNTS_ADMIN) {
      return null;
    }
    return teams.findByAccountantUserId(actor.userId()).stream()
        .map(Team::getHrUserId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  /** Only in-scope APPROVED employees are visible; anything else reads as not-found (§2). */
  private Employee loadApproved(IhrmsPrincipal.User actor, String employeeId) {
    List<String> hrIds = hrScope(actor);
    return employees
        .findById(employeeId)
        .filter(e -> e.getStatus() == EmployeeStatus.APPROVED)
        .filter(e -> hrIds == null || hrIds.contains(e.getOnboardingHrId()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
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
