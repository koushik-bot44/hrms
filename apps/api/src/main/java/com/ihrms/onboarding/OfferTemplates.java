package com.ihrms.onboarding;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Loads the single-source Offer Letter fragment ({@code resources/onboarding/text/offer.html}) and performs
 * token substitution — the SAME mechanism as the agreements / offboarding documents: one substituted body
 * drives both the on-screen read view (the invited employee's offer screen) and the accepted PDF. Text token
 * values are HTML-escaped; {@code {{SIGNATURE_IMG}}} is injected raw (the employee's acceptance signature).
 */
@Component
public class OfferTemplates {

  private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[A-Z_]+}}");
  private static final String FRAGMENT_FILE = "onboarding/text/offer.html";
  private final AtomicReference<String> cache = new AtomicReference<>();

  private String fragment() {
    String cached = cache.get();
    if (cached != null) {
      return cached;
    }
    try {
      String loaded =
          StreamUtils.copyToString(
              new ClassPathResource(FRAGMENT_FILE).getInputStream(), StandardCharsets.UTF_8);
      cache.set(loaded);
      return loaded;
    } catch (IOException e) {
      throw new UncheckedIOException("Missing offer fragment: " + FRAGMENT_FILE, e);
    }
  }

  /** Substitute {@code textTokens} (escaped) + {@code signatureImgHtml} (raw); strip any unfilled token. */
  public String render(Map<String, String> textTokens, String signatureImgHtml) {
    String body = fragment();
    for (Map.Entry<String, String> e : textTokens.entrySet()) {
      body = body.replace("{{" + e.getKey() + "}}", escape(e.getValue()));
    }
    body = body.replace("{{SIGNATURE_IMG}}", signatureImgHtml == null ? "" : signatureImgHtml);
    return LEFTOVER.matcher(body).replaceAll("");
  }

  /** HTML-escape a token value (the fragment is parsed as XML by the PDF renderer). */
  static String escape(String s) {
    if (s == null) {
      return "";
    }
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }
}
