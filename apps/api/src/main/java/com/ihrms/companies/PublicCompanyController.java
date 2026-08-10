package com.ihrms.companies;

import com.ihrms.domain.repository.CompanyRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal PUBLIC (unauthenticated) company lookup by slug — the ONLY thing the slugged sign-in doors need
 * from an anonymous visitor: confirm the slug exists (else 404 the door) and show the company name for context
 * (slug routing Stage 3). It exposes NOTHING beyond {name} (existence + display name — the inherent disclosure
 * of any per-company login URL); the authenticated {@code /companies/by-slug/*} resolver is untouched. A DELETED
 * (archived) company is treated as absent (404), matching the active-only slug semantics.
 */
@RestController
public class PublicCompanyController {

  private final CompanyRepository companies;

  public PublicCompanyController(CompanyRepository companies) {
    this.companies = companies;
  }

  @GetMapping("/public/companies/{slug}")
  public PublicCompanyView bySlug(@PathVariable String slug) {
    return companies
        .findBySlugIgnoreCase(slug)
        .filter(c -> c.getDeletedAt() == null)
        .map(c -> new PublicCompanyView(c.getSlug(), c.getName()))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
  }

  /** Public company context for a sign-in door — display name only. */
  public record PublicCompanyView(String slug, String name) {}
}
