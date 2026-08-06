package com.ihrms.offboarding;

import com.ihrms.domain.enums.OffboardingDocType;
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
 * Loads the single-source offboarding document fragments ({@code resources/offboarding/text/*.html}) and
 * performs token substitution — the SAME mechanism as {@link com.ihrms.agreement.AgreementTemplates}: one
 * substituted body drives both the on-screen read view and the PDF. Text token values are HTML-escaped (the
 * fragments are parsed as XML by the PDF renderer); {@code {{SIGNATURE_IMG}}} is injected raw.
 */
@Component
public class OffboardingDocumentTemplates {

  private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[A-Z_]+}}");
  private final Map<OffboardingDocType, String> cache = new ConcurrentHashMap<>();

  public String title(OffboardingDocType type) {
    return switch (type) {
      case EXIT_FORMALITIES -> "Separation & Exit Formalities";
      case SETTLEMENT -> "Settlement Agreement";
      case SEPARATION -> "Employee Separation Agreement and Release";
    };
  }

  private String fragmentFile(OffboardingDocType type) {
    return switch (type) {
      case EXIT_FORMALITIES -> "offboarding/text/exit.html";
      case SETTLEMENT -> "offboarding/text/settlement.html";
      case SEPARATION -> "offboarding/text/separation.html";
    };
  }

  private String fragment(OffboardingDocType type) {
    return cache.computeIfAbsent(
        type,
        t -> {
          try {
            return StreamUtils.copyToString(
                new ClassPathResource(fragmentFile(t)).getInputStream(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException("Missing offboarding fragment: " + fragmentFile(t), e);
          }
        });
  }

  /** Substitute {@code textTokens} (escaped) + {@code signatureImgHtml} (raw); strip any unfilled token. */
  public String render(
      OffboardingDocType type, Map<String, String> textTokens, String signatureImgHtml) {
    return render(type, textTokens, Map.of(), signatureImgHtml);
  }

  /**
   * The DISPLAY variant: {@code textTokens} escaped as usual, plus {@code rawTokens} (inline field markers)
   * injected RAW at their placeholders — the same raw-injection the signature slot uses. The PDF render uses
   * the escaped-only overload above, so this changes nothing about the generated PDF.
   */
  public String render(
      OffboardingDocType type,
      Map<String, String> textTokens,
      Map<String, String> rawTokens,
      String signatureImgHtml) {
    String body = fragment(type);
    for (Map.Entry<String, String> e : textTokens.entrySet()) {
      body = body.replace("{{" + e.getKey() + "}}", escape(e.getValue()));
    }
    for (Map.Entry<String, String> e : rawTokens.entrySet()) {
      body = body.replace("{{" + e.getKey() + "}}", e.getValue()); // raw markers
    }
    body = body.replace("{{SIGNATURE_IMG}}", signatureImgHtml == null ? "" : signatureImgHtml);
    return LEFTOVER.matcher(body).replaceAll("");
  }

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
