package com.ihrms.auth;

/**
 * Thrown when a credential authenticates SUCCESSFULLY but at the wrong audience-specific door (§6) — a staff
 * User at the WORKSPACE door, or a credentialed Employee at the STAFF door. Carries a door-appropriate message
 * and maps (in {@code ApiExceptionHandler}) to a {@code 403} with {@code code:"PORTAL_MISMATCH"} so the UI can
 * show the "wrong door" state + a cross-link. Refused only AFTER authentication, so it reveals nothing an
 * attacker couldn't learn by signing in at the correct door.
 */
public class PortalMismatchException extends RuntimeException {
  public PortalMismatchException(String message) {
    super(message);
  }
}
