package com.ihrms.iclock;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Reads parameters straight out of the raw query string.
 *
 * <p>Deliberately avoids {@code getParameter()} / {@code @RequestParam}. On a POST with
 * {@code application/x-www-form-urlencoded}, the servlet container merges the request BODY into the
 * parameter map — so asking for a query parameter would consume the ATTLOG payload before the
 * handler could read it. That content type is one of the open dialect questions, so the safe move is
 * to never touch the parameter map on this path at all.
 *
 * <p>Matching is case-insensitive because firmware varies in casing ({@code SN} vs {@code sn}).
 */
final class IclockQuery {

  private IclockQuery() {}

  /** @return the first value for {@code name}, or null if absent or blank. */
  static String param(String queryString, String name) {
    if (queryString == null || queryString.isEmpty() || name == null) {
      return null;
    }
    String target = name.toLowerCase(Locale.ROOT);
    for (String pair : queryString.split("&")) {
      if (pair.isEmpty()) {
        continue;
      }
      int eq = pair.indexOf('=');
      String key = eq < 0 ? pair : pair.substring(0, eq);
      if (!key.toLowerCase(Locale.ROOT).equals(target)) {
        continue;
      }
      if (eq < 0) {
        return null; // present but valueless
      }
      String value = decode(pair.substring(eq + 1));
      return value.isBlank() ? null : value;
    }
    return null;
  }

  private static String decode(String raw) {
    try {
      return URLDecoder.decode(raw, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException malformedEscape) {
      // A device sending a stray '%' must not break ingest — keep the raw form.
      return raw;
    }
  }
}
