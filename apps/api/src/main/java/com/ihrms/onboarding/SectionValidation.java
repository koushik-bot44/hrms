package com.ihrms.onboarding;

import com.ihrms.domain.enums.SectionKey;
import com.ihrms.web.ValidationException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Per-section {@code data} validation + normalization (api-contract.md §3.7, mirroring the
 * shared SECTION_DATA_SCHEMAS). Returns the cleaned map to store, or throws a 400 carrying
 * {@code "field: message"} strings. The web validates the same rules client-side; this is the
 * authoritative server-side gate.
 */
public final class SectionValidation {

  private static final Pattern DOB = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
  private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
  private static final Pattern AADHAAR = Pattern.compile("^\\d{4}$");

  private SectionValidation() {}

  public static Map<String, Object> validate(SectionKey key, Map<String, Object> data) {
    Map<String, Object> in = data == null ? Map.of() : data;
    List<String> errors = new ArrayList<>();
    Map<String, Object> out = new LinkedHashMap<>();

    switch (key) {
      case PERSONAL -> {
        requiredString(in, out, errors, "fullName", "Full name is required", 2, 120);
        requiredPattern(in, out, errors, "dateOfBirth", DOB, "Use YYYY-MM-DD");
        requiredString(in, out, errors, "phone", "Enter a valid phone number", 7, 20);
        requiredString(in, out, errors, "addressLine", "Address is required", 3, 200);
        requiredString(in, out, errors, "city", "City is required", 2, 80);
      }
      case BACKGROUND -> {
        optionalString(in, out, errors, "previousCompany", 120);
        optionalNumber(in, out, errors, "yearsOfExperience", 0, 60);
        optionalString(in, out, errors, "notes", 1000);
      }
      case GOVERNMENT -> {
        String pan = stringOrNull(in.get("panNumber"));
        if (pan == null || !PAN.matcher(pan.trim().toUpperCase()).matches()) {
          errors.add("panNumber: Enter a valid PAN (e.g. ABCDE1234F)");
        } else {
          out.put("panNumber", pan.trim().toUpperCase());
        }
        optionalPattern(in, out, errors, "aadhaarLast4", AADHAAR, "Last 4 digits only");
      }
    }

    if (!errors.isEmpty()) {
      throw new ValidationException(errors);
    }
    return out;
  }

  private static void requiredString(
      Map<String, Object> in,
      Map<String, Object> out,
      List<String> errors,
      String field,
      String message,
      int min,
      int max) {
    String s = stringOrNull(in.get(field));
    if (s == null) {
      errors.add(field + ": " + message);
      return;
    }
    String t = s.trim();
    if (t.length() < min) {
      errors.add(field + ": " + message);
    } else if (t.length() > max) {
      errors.add(field + ": must be at most " + max + " characters");
    } else {
      out.put(field, t);
    }
  }

  private static void requiredPattern(
      Map<String, Object> in,
      Map<String, Object> out,
      List<String> errors,
      String field,
      Pattern pattern,
      String message) {
    String s = stringOrNull(in.get(field));
    if (s == null || !pattern.matcher(s.trim()).matches()) {
      errors.add(field + ": " + message);
    } else {
      out.put(field, s.trim());
    }
  }

  private static void optionalString(
      Map<String, Object> in,
      Map<String, Object> out,
      List<String> errors,
      String field,
      int max) {
    if (!in.containsKey(field) || in.get(field) == null) {
      return;
    }
    String s = stringOrNull(in.get(field));
    if (s == null) {
      errors.add(field + ": must be text");
      return;
    }
    String t = s.trim();
    if (t.length() > max) {
      errors.add(field + ": must be at most " + max + " characters");
    } else {
      out.put(field, t); // preserve "" so a cleared optional round-trips
    }
  }

  private static void optionalPattern(
      Map<String, Object> in,
      Map<String, Object> out,
      List<String> errors,
      String field,
      Pattern pattern,
      String message) {
    if (!in.containsKey(field) || in.get(field) == null) {
      return;
    }
    String s = stringOrNull(in.get(field));
    if (s == null) {
      errors.add(field + ": " + message);
      return;
    }
    String t = s.trim();
    if (t.isEmpty()) {
      out.put(field, "");
    } else if (!pattern.matcher(t).matches()) {
      errors.add(field + ": " + message);
    } else {
      out.put(field, t);
    }
  }

  private static void optionalNumber(
      Map<String, Object> in,
      Map<String, Object> out,
      List<String> errors,
      String field,
      int min,
      int max) {
    if (!in.containsKey(field) || in.get(field) == null) {
      return;
    }
    Object v = in.get(field);
    double value;
    if (v instanceof Number n) {
      value = n.doubleValue();
    } else if (v instanceof String s && !s.isBlank()) {
      try {
        value = Double.parseDouble(s.trim());
      } catch (NumberFormatException e) {
        errors.add(field + ": must be a number");
        return;
      }
    } else if (v instanceof String) {
      return; // empty string -> treat as absent
    } else {
      errors.add(field + ": must be a number");
      return;
    }
    if (value < min || value > max) {
      errors.add(field + ": must be between " + min + " and " + max);
    } else if (value == Math.floor(value)) {
      out.put(field, (int) value);
    } else {
      out.put(field, value);
    }
  }

  private static String stringOrNull(Object v) {
    return v instanceof String s ? s : null;
  }
}
