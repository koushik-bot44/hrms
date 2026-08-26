package com.ihrms.iclock;

import java.util.regex.Pattern;

/**
 * Canonicalisation of terminal enrolment numbers (decision D3). Pure and static — unit-testable with
 * no Spring context.
 *
 * <p><b>Why this exists.</b> The live fleet sends some pins zero-padded and others not: mined
 * enrolment records show {@code 000261}, {@code 000262} and {@code 02919}, and those exact padded
 * forms appear in {@code iclock_raw_punches.devicePin}. Without a single canonical form, an operator
 * importing {@code 261} would never match a punch carrying {@code 000261}, and the failure mode is
 * silent: the punch simply resolves to nobody, indistinguishable from an unenrolled finger.
 *
 * <p>The rule is applied on BOTH sides — at ingest before resolution, and at every mapping write — so
 * the two can never disagree. {@code iclock_raw_punches.rawLine} keeps the device's original text
 * verbatim, so the padded form is never lost, only normalised for matching.
 */
final class IclockPin {

  /** The canonical shape, mirrored by the {@code iclock_employee_pins_pin_canonical} CHECK in V43. */
  private static final Pattern CANONICAL = Pattern.compile("^[1-9][0-9]{0,19}$");

  private IclockPin() {}

  /**
   * Strips surrounding whitespace and leading zeros.
   *
   * @return the canonical pin, or null if the input is null/blank. An all-zero input canonicalises to
   *     {@code "0"}, which deliberately FAILS {@link #isValid} rather than being silently accepted —
   *     a terminal enrolment of zero is not a real employee, and mapping it would shadow real punches.
   */
  static String canonical(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    int i = 0;
    while (i < trimmed.length() - 1 && trimmed.charAt(i) == '0') {
      i++;
    }
    return trimmed.substring(i);
  }

  /** True when the value is a well-formed canonical pin that the DB CHECK will also accept. */
  static boolean isValid(String canonical) {
    return canonical != null && CANONICAL.matcher(canonical).matches();
  }

  /**
   * Canonicalise and validate in one step.
   *
   * @return the canonical pin, or null when the input could never be a usable enrolment number
   *     (blank, all zeros, non-numeric, or longer than 20 digits)
   */
  static String canonicalOrNull(String raw) {
    String c = canonical(raw);
    return isValid(c) ? c : null;
  }
}
