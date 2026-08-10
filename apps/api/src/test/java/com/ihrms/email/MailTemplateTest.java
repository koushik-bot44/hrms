package com.ihrms.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The branded email wrapper's header brand line is slugged for a COMPANY-user email (hrorg.in/{slug}) and plain
 * for a platform user (hrorg.in) — matching their links (slug routing Stage 3). Pure unit, no network.
 */
class MailTemplateTest {

  @Test
  void brandLineIsSluggedForACompanyUser() {
    String html = MailTemplate.render("Your sign-in code is 123456", "acme-corp");
    assertThat(html).contains("hrorg.in/acme-corp");
    assertThat(html).contains("Integrated HR Management Services"); // subtitle unchanged
    assertThat(html).contains("123456"); // body carried through
  }

  @Test
  void brandLineIsPlainForAPlatformUser() {
    String html = MailTemplate.render("Some notification", null);
    assertThat(html).contains(">hrorg.in<"); // brand element, no slug
    assertThat(html).doesNotContain("hrorg.in/");
  }
}
