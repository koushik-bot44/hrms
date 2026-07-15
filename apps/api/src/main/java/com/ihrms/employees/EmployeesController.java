package com.ihrms.employees;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.employees.dto.EmployeeDtos.EmployeePage;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Employee onboarding (contract §3.5). HR-only (URL rule + @PreAuthorize); own company. POST -> 201. */
@RestController
@RequestMapping("/employees")
@PreAuthorize("hasRole('HR')")
public class EmployeesController {

  private final EmployeesService employees;

  public EmployeesController(EmployeesService employees) {
    this.employees = employees;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public OnboardEmployeeResult onboard(
      @Valid @RequestBody OnboardEmployeeRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return employees.onboard(body, actor, request.getRemoteAddr());
  }

  /**
   * The employee list: for HR their own onboarded queue; for a COMPANY_ADMIN the whole company (§6) —
   * so the admin can find any approved employee to manage their mailbox. Search (name/email/ID) +
   * status, paginated. Overrides the class-level HR-only rule.
   */
  @GetMapping
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN')")
  public EmployeePage list(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) EmployeeStatus status,
      @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return employees.queue(actor, search, status, pageable);
  }
}
