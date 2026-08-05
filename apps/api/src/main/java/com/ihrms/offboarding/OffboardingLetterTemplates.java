package com.ihrms.offboarding;

import com.ihrms.domain.enums.RequestType;
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
 * Loads the single-source offboarding LETTER fragments ({@code resources/offboarding/text/{relieving,
 * experience}.html}) and performs token substitution — the SAME mechanism as {@link
 * OffboardingDocumentTemplates}: one substituted body drives both the on-screen preview and the PDF. Unlike
 * the employee documents these are company-issued (no employee signature), so there is no {@code
 * {{SIGNATURE_IMG}}} slot. The leftover-token sweep is case-insensitive because the Experience letter carries
 * cased pronoun tokens ({@code {{he_she}}}, {@code {{He_She}}}, …) alongside the uppercase field tokens.
 */
@Component
public class OffboardingLetterTemplates {

  private static final Pattern LEFTOVER = Pattern.compile("\\{\\{[A-Za-z_]+}}");
  private final Map<RequestType, String> cache = new ConcurrentHashMap<>();

  private String fragmentFile(RequestType type) {
    return switch (type) {
      case RELIEVING_LETTER -> "offboarding/text/relieving.html";
      case EXPERIENCE_LETTER -> "offboarding/text/experience.html";
      default -> throw new IllegalArgumentException("Not a letter type: " + type);
    };
  }

  private String fragment(RequestType type) {
    return cache.computeIfAbsent(
        type,
        t -> {
          try {
            return StreamUtils.copyToString(
                new ClassPathResource(fragmentFile(t)).getInputStream(), StandardCharsets.UTF_8);
          } catch (IOException e) {
            throw new UncheckedIOException("Missing offboarding letter fragment: " + fragmentFile(t), e);
          }
        });
  }

  /** Substitute {@code tokens} (HTML-escaped) into the fragment; strip any unfilled token. */
  public String render(RequestType type, Map<String, String> tokens) {
    String body = fragment(type);
    for (Map.Entry<String, String> e : tokens.entrySet()) {
      body = body.replace("{{" + e.getKey() + "}}", OffboardingDocumentTemplates.escape(e.getValue()));
    }
    return LEFTOVER.matcher(body).replaceAll("");
  }
}
