package com.ihrms.auth;

import com.ihrms.domain.model.Employee;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
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

  private final EmployeeRepository employees;
  private final TeamRepository teams;

  public AuthorizationService(EmployeeRepository employees, TeamRepository teams) {
    this.employees = employees;
    this.teams = teams;
  }

  /** The companyId a principal is locked to, or null for SUPER_ADMIN (all companies). */
  public String tenantCompanyId(IhrmsPrincipal principal) {
    if (principal instanceof IhrmsPrincipal.Employee e) {
      return e.companyId();
    }
    IhrmsPrincipal.User u = (IhrmsPrincipal.User) principal;
    return u.role() == com.ihrms.domain.enums.UserRole.SUPER_ADMIN ? null : u.companyId();
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
      case COMPANY_ADMIN -> employee.companyId().equals(u.companyId());
      case HR ->
          employee.companyId().equals(u.companyId())
              && employee.onboardingHrId().equals(u.userId());
      case MANAGER ->
          employee.companyId().equals(u.companyId())
              && u.companyId() != null
              && teams.existsByCompanyIdAndManagerUserIdAndHrUserId(
                  u.companyId(), u.userId(), employee.onboardingHrId());
    };
  }
}
