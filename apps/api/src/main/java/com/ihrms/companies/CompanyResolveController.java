package com.ihrms.companies;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.companies.dto.CompanyDtos.CompanyRefView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resolve a company by its URL slug (Stage 2 routing). Kept OUT of {@link CompaniesController} (which is
 * SUPER_ADMIN-only) so a company-scoped session can resolve its OWN company: SecurityConfig opens
 * {@code GET /companies/by-slug/*} to any authenticated principal and
 * {@link CompaniesService#resolveBySlug} enforces the per-role scope (SUPER_ADMIN / ACCOUNTS_ADMIN any;
 * a company-scoped session only its own company; unauthorized or unknown slug → 404, never leaking).
 */
@RestController
public class CompanyResolveController {

  private final CompaniesService companies;

  public CompanyResolveController(CompaniesService companies) {
    this.companies = companies;
  }

  @GetMapping("/companies/by-slug/{slug}")
  public CompanyRefView resolve(
      @PathVariable String slug, @AuthenticationPrincipal IhrmsPrincipal principal) {
    return companies.resolveBySlug(slug, principal);
  }
}
