package com.ihrms.auth;

/**
 * Which slugged sign-in door a credential login was submitted at (§6). The audience-specific doors send
 * this so the API can refuse a mismatched principal AFTER a successful authentication (a staff User at the
 * WORKSPACE door, or a credentialed Employee at the STAFF door). The general top-level {@code /login} sends
 * none — it accepts any valid credential and routes by role (platform roles + legacy links).
 */
public enum LoginAudience {
  /** {@code /{slug}/login} — company STAFF (a {@link IhrmsPrincipal.User}). */
  STAFF,
  /** {@code /{slug}/workspace/login} — an approved, credentialed {@link IhrmsPrincipal.Employee}. */
  WORKSPACE
}
