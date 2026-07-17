package com.ihrms.accountant;

import com.ihrms.accountant.dto.AccountantDtos.AccountantStatus;
import com.ihrms.accountant.dto.AccountantDtos.AccountantView;
import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeePage;
import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeeRow;
import com.ihrms.accountant.dto.AccountantDtos.MyTeamView;
import com.ihrms.accountant.dto.AccountantDtos.ViewerCompanyRow;
import com.ihrms.accountant.dto.AccountantDtos.ViewerTeamRow;
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
import java.util.stream.Stream;
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

  // --- Team-wise browsing (§2) ---------------------------------------------------
  //
  // ACCOUNTS_ADMIN drills COMPANY -> TEAM -> EMPLOYEE across every company; the ACCOUNTANT never picks
  // a company/team (they have exactly one) — they read only their own team's roster. A "team's
  // employees" are the APPROVED employees onboarded by that team's HR (the same onboardingHr resolution
  // as the mail graph / manager scope). All READ-ONLY.

  private static final String DELETED = "DELETED";

  /** Top level of the ACCOUNTS_ADMIN drilldown: the active companies with team + approved counts. */
  public List<ViewerCompanyRow> companies(IhrmsPrincipal.User actor) {
    requireAccountsAdmin(actor);
    return companies.findAll().stream()
        .filter(c -> !DELETED.equals(c.getStatus()))
        .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
        .map(
            c ->
                new ViewerCompanyRow(
                    c.getId(),
                    c.getName(),
                    c.getCode(),
                    teams.findByCompanyId(c.getId()).size(),
                    employees.countByCompanyIdAndStatus(c.getId(), EmployeeStatus.APPROVED)))
        .toList();
  }

  /** A company's teams (ACCOUNTS_ADMIN, any company): HR/Manager names + approved-employee count. */
  public List<ViewerTeamRow> teamsOfCompany(IhrmsPrincipal.User actor, String companyId) {
    requireAccountsAdmin(actor);
    List<Team> ts = teams.findByCompanyIdOrderByCreatedAtDesc(companyId);
    Set<String> userIds =
        ts.stream()
            .flatMap(t -> Stream.of(t.getHrUserId(), t.getManagerUserId()))
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    Map<String, String> names =
        users.findAllById(userIds).stream()
            .collect(Collectors.toMap(User::getId, User::getName));
    return ts.stream()
        .map(
            t ->
                new ViewerTeamRow(
                    t.getId(),
                    t.getName(),
                    t.getCompanyId(),
                    t.getHrUserId() == null ? null : names.get(t.getHrUserId()),
                    t.getManagerUserId() == null ? null : names.get(t.getManagerUserId()),
                    t.getHrUserId() == null
                        ? 0
                        : employees.countByOnboardingHrIdAndStatus(
                            t.getHrUserId(), EmployeeStatus.APPROVED)))
        .toList();
  }

  /**
   * A team's APPROVED employees. ACCOUNTS_ADMIN may read any team; an ACCOUNTANT may read ONLY a team
   * they are the accountant of (else 404 — never widen). Employees = onboardingHr == the team's HR,
   * tenant-filtered by the team's company.
   */
  public ApprovedEmployeePage teamEmployees(
      IhrmsPrincipal.User actor, String teamId, String search, Pageable pageable) {
    Team team = assertViewableTeam(actor, teamId);
    String hrId = team.getHrUserId();
    String companyId = team.getCompanyId();
    Specification<Employee> spec =
        (root, q, cb) -> {
          List<Predicate> p = new ArrayList<>();
          p.add(cb.equal(root.get("status"), EmployeeStatus.APPROVED));
          p.add(cb.equal(root.get("companyId"), companyId)); // tenant filter (§6)
          p.add(hrId == null ? cb.disjunction() : cb.equal(root.get("onboardingHrId"), hrId));
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

  /** The ACCOUNTANT's own team descriptor (roster header). {@code null} if none is assigned. */
  public MyTeamView myTeam(IhrmsPrincipal.User actor) {
    if (actor.role() != UserRole.ACCOUNTANT) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the team Accountant has a team");
    }
    Team t = teams.findByAccountantUserId(actor.userId()).stream().findFirst().orElse(null);
    if (t == null) {
      return null;
    }
    String companyName =
        companies.findById(t.getCompanyId()).map(Company::getName).orElse(null);
    return new MyTeamView(
        t.getId(),
        t.getName(),
        t.getCompanyId(),
        companyName,
        t.getHrUserId() == null ? null : userName(t.getHrUserId()),
        t.getManagerUserId() == null ? null : userName(t.getManagerUserId()));
  }

  /**
   * Authorize a team read for a viewer (§2), reused by the roster + the attendance analytics:
   * ACCOUNTS_ADMIN may view any team; an ACCOUNTANT may view ONLY a team they are the accountant of
   * (else 404 — never reveal existence, never widen scope).
   */
  public Team assertViewableTeam(IhrmsPrincipal.User actor, String teamId) {
    Team team =
        teams
            .findById(teamId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found"));
    if (actor.role() == UserRole.ACCOUNTANT && !ownsTeam(actor, teamId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Team not found");
    }
    return team;
  }

  /** Authorize an employee read for a viewer (§2): the same approved-only, in-scope gate as the record view. */
  public Employee assertViewableEmployee(IhrmsPrincipal.User actor, String employeeId) {
    return loadApproved(actor, employeeId);
  }

  private boolean ownsTeam(IhrmsPrincipal.User actor, String teamId) {
    return teams.findByAccountantUserId(actor.userId()).stream()
        .anyMatch(t -> t.getId().equals(teamId));
  }

  private String userName(String userId) {
    return users.findById(userId).map(User::getName).orElse(null);
  }

  private void requireAccountsAdmin(IhrmsPrincipal.User actor) {
    if (actor.role() != UserRole.ACCOUNTS_ADMIN) {
      // The team Accountant browses only their own team — no company/team picking.
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Cross-company browsing is Accounts-Admin only");
    }
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
