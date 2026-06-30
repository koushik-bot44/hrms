package com.ihrms.companies;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.companies.dto.CompanyDtos.CompanyDetailView;
import com.ihrms.companies.dto.CompanyDtos.CompanySummaryView;
import com.ihrms.companies.dto.CompanyDtos.CreateCompanyRequest;
import com.ihrms.companies.dto.CompanyDtos.ProvisionAdminRequest;
import com.ihrms.companies.dto.CompanyDtos.ProvisionAdminResult;
import com.ihrms.companies.dto.CompanyDtos.UpdateCompanyRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Company management (contract §3.3). SUPER_ADMIN-only (URL rule + @PreAuthorize). POST -> 201. */
@RestController
@RequestMapping("/companies")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class CompaniesController {

  private final CompaniesService companies;

  public CompaniesController(CompaniesService companies) {
    this.companies = companies;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CompanyDetailView create(
      @Valid @RequestBody CreateCompanyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.create(body, actor, request.getRemoteAddr());
  }

  @GetMapping
  public List<CompanySummaryView> list() {
    return companies.list();
  }

  @GetMapping("/{id}")
  public CompanyDetailView get(@PathVariable String id) {
    return companies.getDetail(id);
  }

  @PatchMapping("/{id}")
  public CompanyDetailView update(
      @PathVariable String id,
      @Valid @RequestBody UpdateCompanyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.update(id, body, actor, request.getRemoteAddr());
  }

  @PostMapping("/{id}/admin")
  @ResponseStatus(HttpStatus.CREATED)
  public ProvisionAdminResult provisionAdmin(
      @PathVariable String id,
      @Valid @RequestBody ProvisionAdminRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return companies.provisionAdmin(id, body, actor, request.getRemoteAddr());
  }
}
