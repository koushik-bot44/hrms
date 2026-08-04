package com.ihrms.agreement;

import com.ihrms.domain.enums.EmployeeAgreementType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Loads the single-source agreement body fragments ({@code resources/agreements/text/*.html}) and performs
 * token substitution. The SAME substituted body drives both the on-screen read view and the PDF (§Agreements),
 * so the two can never diverge — the caller supplies a READ token map (prefills + blanks) or a PDF token map
 * (all filled values). Text token values are HTML-escaped (the fragments are parsed as XML by the PDF
 * renderer); {@code {{SIGNATURE_IMG}}} is injected raw (it is an &lt;img&gt; element we build).
 */
@Component
public class AgreementTemplates {

  private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[A-Z_]+}}");
  private final Map<EmployeeAgreementType, String> cache = new ConcurrentHashMap<>();

  /** The document title (also the letterhead-less heading is inside the fragment). */
  public String title(EmployeeAgreementType type) {
    return switch (type) {
      case AUP -> "Acceptable Use Policy (AUP)";
      case NDA -> "Non-Disclosure & Non-Compete Agreement";
      case NOTICE_PERIOD -> "Notice Period Conduct Guidelines";
    };
  }

  private String fragmentFile(EmployeeAgreementType type) {
    return switch (type) {
      case AUP -> "agreements/text/aup.html";
      case NDA -> "agreements/text/nda.html";
      case NOTICE_PERIOD -> "agreements/text/notice.html";
    };
  }

  private String fragment(EmployeeAgreementType type) {
    return cache.computeIfAbsent(
        type,
        t -> {
          try {
            return StreamUtils.copyToString(
                new ClassPathResource(fragmentFile(t)).getInputStream(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException("Missing agreement fragment: " + fragmentFile(t), e);
          }
        });
  }

  /**
   * Substitute {@code textTokens} (HTML-escaped) and {@code signatureImgHtml} (raw) into the fragment. Any
   * token the caller did not supply is stripped to empty, so raw {@code {{...}}} braces never reach output.
   */
  public String render(
      EmployeeAgreementType type, Map<String, String> textTokens, String signatureImgHtml) {
    String body = fragment(type);
    for (Map.Entry<String, String> e : textTokens.entrySet()) {
      body = body.replace("{{" + e.getKey() + "}}", escape(e.getValue()));
    }
    body = body.replace("{{SIGNATURE_IMG}}", signatureImgHtml == null ? "" : signatureImgHtml);
    return LEFTOVER.matcher(body).replaceAll("");
  }

  /** XML/HTML-escape a text value; null becomes empty. */
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
