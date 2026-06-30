package com.ihrms.employees;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.employees.dto.EmployeeDtos.EmployeeSummaryView;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeRequest;
import com.ihrms.employees.dto.EmployeeDtos.OnboardEmployeeResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

  @GetMapping
  public List<EmployeeSummaryView> list(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return employees.listMine(actor);
  }
}
