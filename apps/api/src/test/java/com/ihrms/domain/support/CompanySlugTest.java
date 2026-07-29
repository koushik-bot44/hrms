package com.ihrms.domain.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit tests for the company slug algorithm (ARCHITECTURE.md §4). No Spring context / DB. */
class CompanySlugTest {

  @Test
  void slugifyFormatsNamesToUrlSafeSlugs() {
    assertThat(CompanySlug.slugify("Acme Inc")).isEqualTo("acme-inc");
    assertThat(CompanySlug.slugify("  Trim  Me  ")).isEqualTo("trim-me");
    assertThat(CompanySlug.slugify("Foo & Bar, LLC.")).isEqualTo("foo-bar-llc");
    assertThat(CompanySlug.slugify("UPPER Case")).isEqualTo("upper-case");
    assertThat(CompanySlug.slugify("multiple---symbols___here")).isEqualTo("multiple-symbols-here");
  }

  @Test
  void slugifyStripsDiacritics() {
    assertThat(CompanySlug.slugify("Café Déjà Vu")).isEqualTo("cafe-deja-vu");
    assertThat(CompanySlug.slugify("Zürich Öl")).isEqualTo("zurich-ol");
  }

  @Test
  void slugifyFallsBackToCompanyForEmptyOrSymbolOnly() {
    assertThat(CompanySlug.slugify("")).isEqualTo("company");
    assertThat(CompanySlug.slugify("   ")).isEqualTo("company");
    assertThat(CompanySlug.slugify("!!! @@@ ###")).isEqualTo("company");
    assertThat(CompanySlug.slugify(null)).isEqualTo("company");
  }

  @Test
  void slugifyTruncatesAtAHyphenBoundary() {
    String longName =
        "One Two Three Four Five Six Seven Eight Nine Ten Eleven Twelve Thirteen";
    String slug = CompanySlug.slugify(longName);
    assertThat(slug.length()).isLessThanOrEqualTo(50);
    assertThat(slug).doesNotEndWith("-").startsWith("one-two-three");
    // Truncation is at a hyphen boundary, so no partial word tail.
    assertThat(slug).doesNotContain("--");
  }

  @Test
  void generateSuffixesOnCollisionWithLowestFreeNumber() {
    Set<String> taken = new HashSet<>();
    assertThat(CompanySlug.generate("Acme", taken, Set.of())).isEqualTo("acme");
    taken.add("acme");
    assertThat(CompanySlug.generate("Acme", taken, Set.of())).isEqualTo("acme-2");
    taken.add("acme-2");
    assertThat(CompanySlug.generate("Acme", taken, Set.of())).isEqualTo("acme-3");
  }

  @Test
  void generateSuffixesReservedWords() {
    assertThat(CompanySlug.generate("Mail", new HashSet<>(), CompanySlug.RESERVED)).isEqualTo("mail-2");
    assertThat(CompanySlug.generate("HR", new HashSet<>(), CompanySlug.RESERVED)).isEqualTo("hr-2");
    assertThat(CompanySlug.generate("Super Admin", new HashSet<>(), CompanySlug.RESERVED))
        .isEqualTo("super-admin-2");
  }

  @Test
  void generateIsCaseInsensitiveAgainstTakenSlugs() {
    // takenLower holds lower-case; slugify already lower-cases, so a differently-cased name collides.
    Set<String> taken = new HashSet<>(Set.of("acme"));
    assertThat(CompanySlug.generate("ACME", taken, Set.of())).isEqualTo("acme-2");
  }

  @Test
  void generateBackfillSequenceProducesDistinctValidSlugs() {
    // Mirrors the V28 backfill loop: dedupe a run of names against a growing taken-set.
    Set<String> taken = new HashSet<>();
    String[] names = {"Acme", "acme", "ACME", "Mail", "Globex"};
    Set<String> produced = new HashSet<>();
    for (String name : names) {
      String slug = CompanySlug.generate(name, taken, CompanySlug.RESERVED);
      taken.add(slug.toLowerCase());
      produced.add(slug);
    }
    assertThat(produced).containsExactlyInAnyOrder("acme", "acme-2", "acme-3", "mail-2", "globex");
  }
}
