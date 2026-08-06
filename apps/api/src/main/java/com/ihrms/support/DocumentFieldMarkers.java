package com.ihrms.support;

/**
 * Inline field markers for the employee-facing documents (§3.2/§3.6). The DISPLAY render substitutes an
 * employee-fill token with a stable marker ({@code <span data-field="KEY" data-kind="text|date">}) instead of
 * the value/blank; the client hydrates a controlled inline input at each marker (the per-document field
 * manifest stays the source of truth for kind/required/prefill, matched by {@code data-field}). The signature
 * slot renders a signature marker. The PDF render is unchanged — it substitutes real values, never markers.
 */
public final class DocumentFieldMarkers {

  /** The reserved data-field for the signature slot (never a real field key). */
  public static final String SIGNATURE_FIELD = "__signature";

  /** The signature-slot marker, injected where the signature image goes in the display render. */
  public static final String SIGNATURE =
      "<span data-field=\"" + SIGNATURE_FIELD + "\" data-kind=\"signature\"></span>";

  private DocumentFieldMarkers() {}

  /** An employee-fill field marker. {@code key} is the manifest/payload key; {@code kind} is text|date|multiline. */
  public static String field(String key, String kind) {
    return "<span data-field=\""
        + escapeAttr(key)
        + "\" data-kind=\""
        + escapeAttr(kind)
        + "\"></span>";
  }

  /** Minimal attribute escaping for the marker keys/kinds (which are our own controlled tokens). */
  private static String escapeAttr(String s) {
    return s == null ? "" : s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;");
  }
}
