package com.ihrms.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.auth.AuthorizationService.EmployeeScope;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * The centralized §6 authorization matrix against real fixtures, including cross-company
 * denial: SUPER_ADMIN (all), COMPANY_ADMIN (own company), HR (own onboarded), MANAGER
 * (employees onboarded by an HR on the manager's team), EMPLOYEE (own record).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class AuthorizationServiceTest {

  @Autowired AuthorizationService authz;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void clean() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"profile_sections\","
            + "\"documents\",\"approval_requests\",\"notifications\",\"audit_logs\","
            + "\"employee_code_sequences\" RESTART IDENTITY CASCADE");
  }

  @Test
  void enforcesHierarchyScopeAndCrossCompanyDenial() {
    Company a = company("AAA");
    Company b = company("BBB");

    User hr1 = user("hr1@a.test", UserRole.HR, a.getId());
    User hr2 = user("hr2@a.test", UserRole.HR, a.getId());
    User manager = user("mgr@a.test", UserRole.MANAGER, a.getId());
    User companyAdminA = user("admin@a.test", UserRole.COMPANY_ADMIN, a.getId());
    User hrB = user("hr@b.test", UserRole.HR, b.getId());

    // The manager manages hr1's team (so hr1's onboardees are in scope, hr2's are not).
    team(a.getId(), hr1.getId(), manager.getId());

    Employee empA = employee(a.getId(), hr1.getId(), "AAA-EMP-000001", "a1@p.test");
    Employee empA2 = employee(a.getId(), hr2.getId(), "AAA-EMP-000002", "a2@p.test");
    Employee empB = employee(b.getId(), hrB.getId(), "BBB-EMP-000001", "b1@p.test");

    EmployeeScope scopeA = scope(empA);
    EmployeeScope scopeA2 = scope(empA2);
    EmployeeScope scopeB = scope(empB);

    // SUPER_ADMIN -> all companies.
    IhrmsPrincipal sa = new IhrmsPrincipal.User("sa", "sa@x.test", "SA", UserRole.SUPER_ADMIN, null, null);
    assertThat(authz.canAccessEmployee(sa, scopeA)).isTrue();
    assertThat(authz.canAccessEmployee(sa, scopeB)).isTrue();

    // COMPANY_ADMIN(A) -> own company only (empB cross-company denied).
    IhrmsPrincipal ca = Principals.of(companyAdminA);
    assertThat(authz.canAccessEmployee(ca, scopeA)).isTrue();
    assertThat(authz.canAccessEmployee(ca, scopeB)).isFalse();

    // HR1 -> own onboarded only.
    IhrmsPrincipal pHr1 = Principals.of(hr1);
    assertThat(authz.canAccessEmployee(pHr1, scopeA)).isTrue();
    assertThat(authz.canAccessEmployee(pHr1, scopeA2)).isFalse();

    // MANAGER -> employees onboarded by an HR on the manager's team.
    IhrmsPrincipal pMgr = Principals.of(manager);
    assertThat(authz.canAccessEmployee(pMgr, scopeA)).isTrue(); // hr1 is on the team
    assertThat(authz.canAccessEmployee(pMgr, scopeA2)).isFalse(); // hr2 is not
    assertThat(authz.canAccessEmployee(pMgr, scopeB)).isFalse(); // cross-company

    // EMPLOYEE -> own record only.
    IhrmsPrincipal pEmp =
        new IhrmsPrincipal.Employee(empA.getId(), empA.getEmployeeCode(), empA.getEmail(), a.getId());
    assertThat(authz.canAccessEmployee(pEmp, scopeA)).isTrue();
    assertThat(authz.canAccessEmployee(pEmp, scopeA2)).isFalse();

    // assertCompany / assertCanAccessEmployee throw 403 across the boundary.
    assertThatThrownBy(() -> authz.assertCompany(ca, b.getId()))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> authz.assertCanAccessEmployee(pHr1, empA2.getId()))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(authz.assertCanAccessEmployee(pHr1, empA.getId()).employeeCode())
        .isEqualTo("AAA-EMP-000001");
  }

  // --- fixtures -------------------------------------------------------------

  private Company company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    return companies.save(c);
  }

  private User user(String email, UserRole role, String companyId) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    return users.save(u);
  }

  private Team team(String companyId, String hrUserId, String managerUserId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName("Team");
    t.setHrUserId(hrUserId);
    t.setManagerUserId(managerUserId);
    return teams.save(t);
  }

  private Employee employee(String companyId, String hrId, String code, String email) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setEmail(email);
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    return employees.save(e);
  }

  private EmployeeScope scope(Employee e) {
    return new EmployeeScope(e.getId(), e.getCompanyId(), e.getOnboardingHrId(), e.getEmployeeCode());
  }
}
