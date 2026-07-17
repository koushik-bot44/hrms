package com.ihrms.employees;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.employees.dto.EmployeeDtos.EmployeePage;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import com.ihrms.employees.dto.EmployeeDtos.SuperAdminOnboardRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

  /**
   * The chosen company's employees (all teams) for the SUPER_ADMIN to browse (§2) — name/email/ID
   * search + status filter, paginated. Backs the SA forms-viewer + Form-2 edit navigation.
   */
  @GetMapping
  public EmployeePage list(
      @PathVariable String companyId,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) EmployeeStatus status,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return employees.queueForCompany(companyId, search, status, pageable);
  }
}
