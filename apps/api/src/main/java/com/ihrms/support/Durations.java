package com.ihrms.support;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parse a jsonwebtoken-style duration ("15m", "7d", "3600s", "1h", "500ms") — matches the archived cookies.ts. */
public final class Durations {

  private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*(ms|s|m|h|d)?$");

  private Durations() {}

  public static Duration parse(String value) {
    Matcher m = PATTERN.matcher(value.trim());
    if (!m.matches()) {
      throw new IllegalArgumentException("Invalid duration: \"" + value + "\"");
    }
    long n = Long.parseLong(m.group(1));
    String unit = m.group(2) == null ? "ms" : m.group(2);
    return switch (unit) {
      case "ms" -> Duration.ofMillis(n);
      case "s" -> Duration.ofSeconds(n);
      case "m" -> Duration.ofMinutes(n);
      case "h" -> Duration.ofHours(n);
      case "d" -> Duration.ofDays(n);
      default -> throw new IllegalArgumentException("Invalid duration unit: " + unit);
    };
  }
}
