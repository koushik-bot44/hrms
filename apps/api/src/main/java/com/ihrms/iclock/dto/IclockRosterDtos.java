package com.ihrms.iclock.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Roster shapes for the Time &amp; Attendance console ({@code /provisioning/iclock/**}).
 *
 * <p>The roster is identity-first: a person is complete without an IHRMS employee. Every field that
 * depends on onboarding — {@code employeeId}, {@code companyId}, even {@code name} — is nullable, and
 * the console is expected to render those gaps as work-to-do rather than as errors.
 */
public final class IclockRosterDtos {

  private IclockRosterDtos() {}

  public record UpsertPersonRequest(
      /** Accepted padded or bare; stored canonical. */
      @NotBlank String pin,
      String name,
      String email,
      /** IHRMS company id, when known. Null is legitimate — see {@code companyLabel}. */
      String companyId,
      /** Free text from the import when no IHRMS company matched. Preserved, never dropped. */
      String companyLabel,
      String team,
      String role,
      Integer lateExemptMin,
      Boolean active,
      /** Reporting exclusion only; does not affect resolution. */
      Boolean excludedFromReports) {}

  public record AssignPersonRequest(@NotBlank String employeeId) {}

  public record PersonView(
      String id,
      String siteId,
      String pin,
      String name,
      String email,
      String companyId,
      String companyName,
      String companyLabel,
      String team,
      String role,
      int lateExemptMin,
      boolean active,
      /** Shown as a badge on the People screen; toggleable there. */
      boolean excludedFromReports,
      String employeeId,
      String employeeName,
      /** True when another person at this site holds the same email — flagged, never blocking. */
      boolean duplicateEmail,
      /** True when the person has no name at all; the console surfaces these for cleanup. */
      boolean unnamed,
      long punchCount) {}
}
