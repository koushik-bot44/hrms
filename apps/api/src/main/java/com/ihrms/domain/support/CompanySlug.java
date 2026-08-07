package com.ihrms.domain.support;

import java.text.Normalizer;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Company slug generation (ARCHITECTURE.md §4). A slug is the PERMANENT, URL-safe identifier for a
 * company — the only human-readable name allowed in a URL. Pure and dependency-free so the create
 * path and the Flyway backfill (V28) share ONE algorithm rather than reimplementing it.
 */
public final class CompanySlug {

  private CompanySlug() {}

  /**
   * App top-level route names a generated slug may never equal (a reserved hit is suffixed {@code -2}
   * exactly like any other collision). Keep in sync with the web app's top-level routes — including the
   * public marketing paths (some live today, the rest reserved proactively so future marketing pages never
   * collide with a company slug). Reservation is not retroactive: existing (immutable) slugs are untouched.
   */
  public static final Set<String> RESERVED =
      Set.of(
          // App top-level routes.
          "super-admin",
          "company-admin",
          "hr",
          "manager",
          "accountant",
          "hierarchy",
          "employee",
          "login",
          "mail",
          "workspace",
          "accounts",
          "api",
          "requests",
          "push",
          "provisioning",
          // Public marketing paths (live + proactively reserved).
          "features",
          "security",
          "contact",
          "about",
          "pricing",
          "product",
          "blog",
          "docs",
          "careers",
          "legal",
          "privacy",
          "terms");

  private static final int MAX_LENGTH = 50;
  private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
  private static final Pattern COMBINING = Pattern.compile("\\p{M}+");

  /**
   * The base slug for a name: trim → lowercase → strip diacritics → each run of non-{@code [a-z0-9]}
   * to a single {@code '-'} → trim leading/trailing {@code '-'} → truncate to {@value #MAX_LENGTH} at a
   * hyphen boundary. An empty result (all-symbol name) falls back to {@code "company"}.
   */
  public static String slugify(String name) {
    if (name == null) {
      return "company";
    }
    String stripped =
        COMBINING.matcher(Normalizer.normalize(name, Normalizer.Form.NFKD)).replaceAll("");
    String slug = trimHyphens(NON_SLUG.matcher(stripped.toLowerCase()).replaceAll("-"));
    if (slug.length() > MAX_LENGTH) {
      slug = slug.substring(0, MAX_LENGTH);
      int lastHyphen = slug.lastIndexOf('-');
      if (lastHyphen > 0) {
        slug = slug.substring(0, lastHyphen);
      }
      slug = trimHyphens(slug);
    }
    return slug.isEmpty() ? "company" : slug;
  }

  /**
   * A UNIQUE, reserved-safe slug for {@code name}: the base slug, or the lowest free {@code -N} suffix
   * (starting at {@code -2}) when the base is already taken (case-insensitive) or reserved.
   * {@code takenLower} holds the already-used slugs in lower case.
   */
  public static String generate(String name, Set<String> takenLower, Set<String> reserved) {
    String base = slugify(name);
    if (isFree(base, takenLower, reserved)) {
      return base;
    }
    for (int n = 2; ; n++) {
      String suffix = "-" + n;
      String head = base;
      if (head.length() + suffix.length() > MAX_LENGTH) {
        head = trimHyphens(base.substring(0, Math.max(1, MAX_LENGTH - suffix.length())));
      }
      String candidate = head + suffix;
      if (isFree(candidate, takenLower, reserved)) {
        return candidate;
      }
    }
  }

  private static boolean isFree(String slug, Set<String> takenLower, Set<String> reserved) {
    return !reserved.contains(slug) && !takenLower.contains(slug);
  }

  private static String trimHyphens(String s) {
    int start = 0;
    int end = s.length();
    while (start < end && s.charAt(start) == '-') {
      start++;
    }
    while (end > start && s.charAt(end - 1) == '-') {
      end--;
    }
    return s.substring(start, end);
  }
}
