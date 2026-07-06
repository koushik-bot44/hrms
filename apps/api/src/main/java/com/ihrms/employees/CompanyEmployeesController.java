package com.ihrms.employees;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import com.ihrms.employees.dto.EmployeeDtos.SuperAdminOnboardRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SUPER_ADMIN onboarding into ANY company (ARCHITECTURE.md §2). Picks company (path) → team (body) →
 * the team's HR (resolved server-side); reuses the SAME onboarding logic as the HR endpoint, so the
 * employee is INVITED with no ID, gets the selection email, and flows to that HR + that team's Manager
 * exactly as usual.
 */
@RestController
@RequestMapping("/companies/{companyId}/employees")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CompanyEmployeesController {

  private final EmployeesService employees;

  public CompanyEmployeesController(EmployeesService employees) {
    this.employees = employees;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OnboardEmployeeResult onboard(
      @PathVariable String companyId,
      @Valid @RequestBody SuperAdminOnboardRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return employees.onboardForCompany(companyId, body, actor, request.getRemoteAddr());
  }
}
