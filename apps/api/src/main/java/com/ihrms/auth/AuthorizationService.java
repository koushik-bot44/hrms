package com.ihrms.auth;

import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import java.util.EnumSet;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * The ONE place hierarchy/tenancy checks live (ARCHITECTURE.md §6) — handlers call these
 * instead of re-deriving scope, and every company-scoped query filters by companyId.
 *
 * <pre>
 *  SUPER_ADMIN   -> all companies
 *  COMPANY_ADMIN -> own companyId
 *  HR            -> own onboarded employees within own company (onboardingHrId == self)
 *  MANAGER       -> employees onboarded by an HR on the manager's team, same company
 *  EMPLOYEE      -> own record only
 * </pre>
 */
@Service
public class AuthorizationService {

  /** The slice of an employee needed to decide access. */
  public record EmployeeScope(
      String id, String companyId, String onboardingHrId, String employeeCode) {}

  /** Company status marking an archived (soft-deleted) company. */
  private static final String DELETED = "DELETED";

  private final EmployeeRepository employees;
  private final TeamRepository teams;
  private final CompanyRepository companies;

  public AuthorizationService(
      EmployeeRepository employees, TeamRepository teams, CompanyRepository companies) {
    this.employees = employees;
    this.teams = teams;
    this.companies = companies;
  }

  /** The companyId a principal is locked to, or null for SUPER_ADMIN (all companies). */
  public String tenantCompanyId(IhrmsPrincipal principal) {
    if (principal instanceof IhrmsPrincipal.Employee e) {
      return e.companyId();
    }
    IhrmsPrincipal.User u = (IhrmsPrincipal.User) principal;
    // SUPER_ADMIN and ACCOUNTS_ADMIN are cross-company (null companyId); everyone else (incl. the
    // team-scoped ACCOUNTANT) is company-locked.
    return u.role() == com.ihrms.domain.enums.UserRole.SUPER_ADMIN
            || u.role() == com.ihrms.domain.enums.UserRole.ACCOUNTS_ADMIN
        ? null
        : u.companyId();
  }

  /** True if {@code companyId} is a DELETED (archived) company. Null (e.g. SUPER_ADMIN) is never. */
  public boolean isCompanyDeleted(String companyId) {
    if (companyId == null) {
      return false;
    }
    return companies.findById(companyId).map(c -> DELETED.equals(c.getStatus())).orElse(false);
  }

  /**
   * The archived-company denial gate (§6): true when the principal belongs to a DELETED company. The
   * Super Admin (null companyId) is never denied — no self-lockout.
   */
  public boolean isPrincipalCompanyDeleted(IhrmsPrincipal principal) {
    return isCompanyDeleted(tenantCompanyId(principal));
  }

  /** Throw 403 unless the principal may act within {@code companyId}. */
  public void assertCompany(IhrmsPrincipal principal, String companyId) {
    if (principal instanceof IhrmsPrincipal.User u
        && u.role() == com.ihrms.domain.enums.UserRole.SUPER_ADMIN) {
      return;
    }
    if (!Objects.equals(tenantCompanyId(principal), companyId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Outside your company scope");
    }
  }

  /** Load an employee and assert access; the single entry point for employee-record access. */
  public EmployeeScope assertCanAccessEmployee(IhrmsPrincipal principal, String employeeId) {
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    EmployeeScope scope =
        new EmployeeScope(
            employee.getId(),
            employee.getCompanyId(),
            employee.getOnboardingHrId(),
            employee.getEmployeeCode());
    if (!canAccessEmployee(principal, scope)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Outside your scope");
    }
    return scope;
  }

  /** Pure §6 decision for employee-record access (also used directly by tests). */
  public boolean canAccessEmployee(IhrmsPrincipal principal, EmployeeScope employee) {
    if (principal instanceof IhrmsPrincipal.Employee e) {
      return employee.id().equals(e.employeeId());
    }
    IhrmsPrincipal.User u = (IhrmsPrincipal.User) principal;
    return switch (u.role()) {
      case SUPER_ADMIN -> true;
      // ACCOUNTS_ADMIN: read-only, cross-company, APPROVED-only — an employeeCode is minted only on
      // approval (§5), so a non-null code == approved. The service also re-checks status explicitly.
      case ACCOUNTS_ADMIN -> employee.employeeCode() != null;
      case COMPANY_ADMIN -> employee.companyId().equals(u.companyId());
      case HR ->
          employee.companyId().equals(u.companyId())
              && employee.onboardingHrId().equals(u.userId());
      case MANAGER ->
          employee.companyId().equals(u.companyId())
              && u.companyId() != null
              && teams.existsByCompanyIdAndManagerUserIdAndHrUserId(
                  u.companyId(), u.userId(), employee.onboardingHrId());
      // ACCOUNTANT: read-only, OWN-TEAM, APPROVED-only — the Manager scope restricted to approved
      // (its team is the one whose HR onboarded the employee).
      case ACCOUNTANT ->
          employee.companyId().equals(u.companyId())
              && u.companyId() != null
              && employee.employeeCode() != null
              && teams.existsByCompanyIdAndAccountantUserIdAndHrUserId(
                  u.companyId(), u.userId(), employee.onboardingHrId());
    };
  }

  // --- Internal mail send graph (§8) ----------------------------------------

  /**
   * A mail participant — a staff account (USER) OR a credentialed employee (EMPLOYEE) — reduced to just
   * what the send graph needs. Ids are globally-unique cuids, so identity is compared by id.
   */
  public record MailParticipant(
      String type, String id, UserRole role, String companyId, String onboardingHrId) {
    public static MailParticipant user(User u) {
      return new MailParticipant("USER", u.getId(), u.getRole(), u.getCompanyId(), null);
    }

    public static MailParticipant employee(Employee e) {
      return new MailParticipant("EMPLOYEE", e.getId(), null, e.getCompanyId(), e.getOnboardingHrId());
    }

    boolean isUser() {
      return "USER".equals(type);
    }
  }

  /**
   * The ONE central check for internal mail: may {@code a} and {@code b} message each other? Symmetric.
   * The graph (§8):
   *
   * <pre>
   *   SUPER_ADMIN   &lt;-&gt; COMPANY_ADMIN   (any company)
   *   SUPER_ADMIN   &lt;-&gt; ACCOUNTS_ADMIN
   *   COMPANY_ADMIN &lt;-&gt; HR | MANAGER | ACCOUNTANT   (SAME company only)
   *   EMPLOYEE      &lt;-&gt; their onboarding HR         (SAME company only)
   * </pre>
   *
   * Everything else — and every cross-company pair — is denied. An employee's ONLY counterpart is the HR
   * who onboarded them (no employee↔employee, no employee↔anyone-else).
   */
  public boolean canSendMail(MailParticipant a, MailParticipant b) {
    if (a == null || b == null || a.id().equals(b.id())) {
      return false; // no self-send
    }
    if (a.isUser() && b.isUser()) {
      return canSendStaff(a, b);
    }
    // Exactly one side is an employee: the only allowed edge is EMPLOYEE <-> their onboarding HR.
    MailParticipant employee = a.isUser() ? b : a;
    MailParticipant other = a.isUser() ? a : b;
    return !employee.isUser() // (rejects employee<->employee: `other` would then be an employee)
        && other.isUser()
        && other.role() == UserRole.HR
        && other.companyId() != null
        && other.companyId().equals(employee.companyId()) // same company
        && other.id().equals(employee.onboardingHrId()); // their onboarding HR specifically
  }

  /** Staff-only graph (both sides USER). */
  private boolean canSendStaff(MailParticipant a, MailParticipant b) {
    EnumSet<UserRole> pair = EnumSet.of(a.role(), b.role());
    // Platform-level pairs — no company constraint (SUPER_ADMIN / ACCOUNTS_ADMIN have no company).
    if (pair.equals(EnumSet.of(UserRole.SUPER_ADMIN, UserRole.COMPANY_ADMIN))
        || pair.equals(EnumSet.of(UserRole.SUPER_ADMIN, UserRole.ACCOUNTS_ADMIN))) {
      return true;
    }
    // Company Admin <-> its own company's HR / Manager / Accountant.
    MailParticipant admin =
        a.role() == UserRole.COMPANY_ADMIN ? a : b.role() == UserRole.COMPANY_ADMIN ? b : null;
    if (admin != null) {
      MailParticipant other = admin == a ? b : a;
      boolean companyStaff =
          other.role() == UserRole.HR
              || other.role() == UserRole.MANAGER
              || other.role() == UserRole.ACCOUNTANT;
      return companyStaff
          && admin.companyId() != null
          && admin.companyId().equals(other.companyId()); // never cross-company
    }
    return false;
  }

  /** Convenience for the staff-only callers (and existing tests): both sides are Users. */
  public boolean canSendMail(User a, User b) {
    if (a == null || b == null) {
      return false;
    }
    return canSendMail(MailParticipant.user(a), MailParticipant.user(b));
  }
}
