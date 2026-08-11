package com.ihrms.web;

import com.ihrms.config.AppProperties;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.repository.CompanyRepository;
import org.springframework.stereotype.Component;

/**
 * The SINGLE place user-facing links are assembled (ARCHITECTURE §6, slug routing Stage 3). A company-scoped
 * user's every link carries their company slug — {@code {WEB_APP_URL}/{slug}/…} for emails and {@code /{slug}/…}
 * for push deep-links; a platform user (no company) is unchanged ({@code {WEB_APP_URL}/…} / {@code /…}). No call
 * site inlines slug concatenation — invite/credential emails, push click URLs, and the email brand line all come
 * through here so the slug can never be forgotten or built two different ways.
 */
@Component
public class WebLinks {

  private final AppProperties props;
  private final CompanyRepository companies;

  public WebLinks(AppProperties props, CompanyRepository companies) {
    this.props = props;
    this.companies = companies;
  }

  /** The company's slug, or null for a platform user / unknown company. */
  public String slugFor(String companyId) {
    if (companyId == null) {
      return null;
    }
    return companies
        .findById(companyId)
        .map(Company::getSlug)
        .filter(s -> s != null && !s.isBlank())
        .orElse(null);
  }

  /** Absolute email link: {@code {WEB_APP_URL}[/{slug}]{path}} (slug omitted for a platform user). */
  public String emailLink(String companyId, String path) {
    return base() + slugSegment(slugFor(companyId)) + path;
  }

  /** Absolute email link when the slug is already in hand (no lookup). */
  public String emailLinkForSlug(String slug, String path) {
    return base() + slugSegment(slug) + path;
  }

  /**
   * A TOP-LEVEL sign-in door link, {@code {WEB_APP_URL}{path}} — NO slug (§6, two-door consolidation). The
   * company slug is applied only AFTER sign-in by {@code homePathForSession}, so the staff door ({@code /login})
   * and the workspace-employee door ({@code /employee/login}) are slug-free for everyone. (The onboarding
   * invite is the one exception — it stays slugged + token-carrying via {@link #emailLinkForSlug}.)
   */
  public String topDoor(String path) {
    return base() + path;
  }

  /**
   * Push click deep-link (relative, resolved by the Service Worker against the web origin):
   * {@code [/{slug}]{path}} for a company user, {@code path} unchanged for a platform user. An ABSOLUTE
   * {@code path} (e.g. the test notification's {WEB_APP_URL}) is returned untouched.
   */
  public String pushPath(String companyId, String path) {
    if (path == null || !path.startsWith("/")) {
      return path; // absolute or empty → leave as-is
    }
    String seg = slugSegment(slugFor(companyId));
    return seg.isEmpty() ? path : seg + path;
  }

  private String slugSegment(String slug) {
    return slug == null || slug.isBlank() ? "" : "/" + slug;
  }

  private String base() {
    return props.webAppUrl().replaceAll("/+$", "");
  }
}
