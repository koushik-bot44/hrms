package com.ihrms.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ihrms.config.AppProperties;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.repository.CompanyRepository;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Link building (slug routing Stage 3): a company user's links carry their slug; a platform user's don't.
 * Pure unit — the company lookup is mocked, no Spring/DB/network. Covers the exact strings (incl. the invite
 * email query encoding), a HYPHENATED slug, and the push relative/absolute rules.
 */
class WebLinksTest {

  private static final String WEB = "http://localhost:3001";

  private final CompanyRepository companies = mock(CompanyRepository.class);
  private final WebLinks links = new WebLinks(props(WEB + "/"), companies); // trailing slash trimmed

  private WebLinks withCompany(String companyId, String slug) {
    Company c = new Company();
    c.setSlug(slug);
    when(companies.findById(companyId)).thenReturn(Optional.of(c));
    return links;
  }

  @Test
  void inviteEmailLinkIsSluggedWithEncodedEmailQuery() {
    withCompany("c1", "acme-corp"); // a hyphenated slug renders correctly
    String url =
        links.emailLinkForSlug(
            "acme-corp",
            "/employee/login?email=" + URLEncoder.encode("a b@x.com", StandardCharsets.UTF_8));
    assertThat(url).isEqualTo("http://localhost:3001/acme-corp/employee/login?email=a+b%40x.com");
  }

  @Test
  void emailLinkSluggedForCompanyUnsluggedForPlatform() {
    withCompany("c1", "acme");
    assertThat(links.emailLink("c1", "/login")).isEqualTo("http://localhost:3001/acme/login");
    // Platform user (no company) → no slug.
    assertThat(links.emailLink(null, "/login")).isEqualTo("http://localhost:3001/login");
    // A company whose slug can't be resolved also falls back to unslugged.
    assertThat(links.emailLink("missing", "/login")).isEqualTo("http://localhost:3001/login");
  }

  @Test
  void pushPathSluggedForCompanyUnchangedForPlatformAndAbsolute() {
    withCompany("c1", "acme-corp");
    assertThat(links.pushPath("c1", "/workspace/agreements"))
        .isEqualTo("/acme-corp/workspace/agreements");
    // Platform user → the path is unchanged (no slug).
    assertThat(links.pushPath(null, "/mail")).isEqualTo("/mail");
    // An absolute URL (e.g. the test notification's WEB_APP_URL) is returned untouched.
    assertThat(links.pushPath("c1", "http://localhost:3001")).isEqualTo("http://localhost:3001");
  }

  @Test
  void slugForResolvesOrNull() {
    withCompany("c1", "acme");
    assertThat(links.slugFor("c1")).isEqualTo("acme");
    assertThat(links.slugFor(null)).isNull();
    assertThat(links.slugFor("missing")).isNull();
  }

  private static AppProperties props(String webAppUrl) {
    return new AppProperties(
        null, webAppUrl, null, null, null, null, null, 0, null, null, null, null, null);
  }
}
