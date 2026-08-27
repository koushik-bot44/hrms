package com.ihrms.iclock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

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
      Boolean excludedFromReports,
      /**
       * NIGHT or DAY. Null leaves the current assignment alone.
       *
       * <p>Validated here as well as by the V47 CHECK, so an unknown value is a 400 naming the
       * accepted ones rather than the 500 a raw constraint violation produces —
       * {@code ddl-auto: validate} never checks CHECK constraints, so the database alone cannot make
       * this a polite failure.
       */
      @Pattern(regexp = "NIGHT|DAY", message = "Shift must be NIGHT or DAY.")
          String shiftProfile) {}

  /**
   * Moving a group of people onto a shift in one action.
   *
   * <p>A bulk action rather than per-person edits because the real input is a confirmed LIST — "these
   * 22 pins work the day shift" — and applying it one row at a time across a 255-person roster invites
   * a half-finished assignment where some of a team are on one shift and the rest on another.
   */
  public record AssignShiftRequest(
      /** Roster person ids. Pins are deliberately not accepted: they are only unique within a site. */
      @NotEmpty(message = "Select at least one person.") List<String> personIds,
      @NotBlank @Pattern(regexp = "NIGHT|DAY", message = "Shift must be NIGHT or DAY.")
          String shiftProfile) {}

  /** What a bulk assignment did, per person, so the operator can see it rather than trust it. */
  public record AssignShiftReport(
      String shiftProfile,
      int changed,
      int alreadyOnIt,
      /**
       * Punches re-dated in the current payroll cycle.
       *
       * <p>Reported because it is the part an operator would otherwise never see: moving somebody to
       * the day shift silently rewrites which day this month's punches belong to, and a number is the
       * difference between that being a deliberate correction and a surprise.
       */
      int punchesRedated,
      /** The first shift day the recompute was allowed to touch. Earlier cycles are settled. */
      java.time.LocalDate recomputedFrom,
      List<AssignShiftRow> rows) {}

  public record AssignShiftRow(String personId, String pin, String name, String from, String to) {}

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
      /**
       * NIGHT or DAY — which shift this person works, and therefore which day their punches file
       * under, when they count as late, and when a long absence is worth alerting on.
       */
      String shiftProfile,
      long punchCount) {}
}
