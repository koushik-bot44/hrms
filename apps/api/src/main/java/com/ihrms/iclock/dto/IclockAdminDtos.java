package com.ihrms.iclock.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Request/response shapes for the iClock admin surface ({@code /provisioning/iclock/**}).
 *
 * <p>Mounted under {@code /provisioning} deliberately: that prefix is already
 * {@code hasRole("SUPER_ADMIN")} in the security chain, so the surface is fail-closed. Anything under
 * {@code /iclock/} would match the unauthenticated {@code permitAll("/iclock/**")} device carve-out
 * first and be served with no JWT, no role and no principal.
 */
public final class IclockAdminDtos {

  private IclockAdminDtos() {}

  // ------------------------------------------------------------------ sites

  public record CreateSiteRequest(
      @NotBlank String name,
      /** IANA zone. Only Asia/Kolkata is accepted until ShiftConfig itself is parameterised. */
      String timezone) {}

  public record RenameSiteRequest(@NotBlank String name) {}

  public record SiteView(
      String id,
      String name,
      String timezone,
      long companyCount,
      long deviceCount,
      long pinCount,
      Instant createdAt) {}

  public record SiteCompanyView(String companyId, String name, String slug, boolean archived) {}

  public record SiteDetailView(
      SiteView site, List<SiteCompanyView> companies, List<DeviceView> devices) {}

  public record LinkCompanyRequest(@NotBlank String companyId) {}

  // ---------------------------------------------------------------- devices

  public record DeviceView(
      String id,
      String serialNumber,
      String name,
      String status,
      String siteId,
      String siteName,
      String area,
      String direction,
      Instant lastSeenAt,
      Instant lastHandshakeAt,
      Instant claimedAt,
      String firmwareInfo,
      long rawPunchCount) {}

  public record ClaimDeviceRequest(
      @NotBlank String siteId,
      @Pattern(regexp = "GATE|CAFETERIA", message = "area must be GATE or CAFETERIA") String area,
      @Pattern(regexp = "IN|OUT|MIXED", message = "direction must be IN, OUT or MIXED")
          String direction,
      String name) {}

  public record RenameDeviceRequest(@NotBlank String name) {}

  /**
   * A role change on an already-claimed terminal.
   *
   * <p>Same validation as the claim, mirroring the V43 CHECK constraints so a bad value is a 400 rather
   * than the 500 a bare constraint produces. Note jakarta's {@code @Pattern} passes null, so
   * {@code @NotBlank} carries the required-ness.
   */
  public record ChangeRoleRequest(
      @NotBlank @Pattern(regexp = "GATE|CAFETERIA", message = "area must be GATE or CAFETERIA")
          String area,
      @NotBlank @Pattern(regexp = "IN|OUT|MIXED", message = "direction must be IN, OUT or MIXED")
          String direction) {}

  /** Which building a terminal moves to. */
  public record MoveBuildingRequest(@NotBlank String siteId) {}

  // ------------------------------------------------------------------- pins

  public record AssignPinRequest(
      @NotBlank String employeeId,
      /** Accepted padded or bare; stored canonical (leading zeros stripped). */
      @NotBlank String pin) {}

  public record PinView(
      String id,
      String pin,
      String employeeId,
      String employeeName,
      String employeeCode,
      String companyId,
      String companyName,
      String employeeStatus) {}

  /**
   * The inbox, split by whether a pin is still punching.
   *
   * <p>With backfill OFF, an archive-only pin is history and needs no action, while a pin punching
   * since the device was claimed is somebody at the gate right now going unattributed. Collapsing
   * the two buries the handful that matter under hundreds of rows of archaeology, so the archive
   * side is deliberately reduced to a count.
   */
  public record UnmappedInbox(
      Instant since,
      /** The action list: pins punching in the live window, heaviest first. */
      List<UnmappedPinView> live,
      int archiveOnlyPins,
      long archiveOnlyPunches) {}

  /** One row of the unmapped-pin inbox: a pin that punched but resolves to nobody. */
  public record UnmappedPinView(
      String pin,
      long punchCount,
      Instant firstSeen,
      Instant lastSeen,
      String siteId,
      String siteName,
      /** UNKNOWN_PIN, DEVICE_UNCLAIMED, ANOMALY_OFFBOARDED, UNPARSEABLE_TIME or NO_PIN. */
      String reason,
      /**
       * Identity suggestion for the operator — the terminal's own enrolment name where one was
       * captured. A SUGGESTION only: these are terse device-register strings ("Web Developer" is a
       * role, not a person), so every created identity still takes a human decision.
       */
      String suggestedName,
      /** Punches in the live window (since the device was claimed). */
      long livePunchCount,
      /**
       * True when the pin DOES resolve to a roster person who is marked inactive. The remedy is to
       * flip them active on the People screen, not to create a duplicate — so the console must say
       * so rather than offering "create person".
       */
      boolean inactivePerson,
      String personId) {}

  // ----------------------------------------------------------------- import

  /**
   * Seed CSV for PIN mapping.
   *
   * <p>Columns: {@code pin, pin_canonical, name, email, company, team, role, late_exempt_min,
   * deleted, source}. Only {@code pin}/{@code pin_canonical}, {@code name} and {@code email} are
   * consumed by P1a; the rest are carried through the report untouched so the operator can see the
   * whole row. {@code late_exempt_min} is explicitly a P2 policy input and is NOT applied here.
   *
   * <p>Employees are matched by EMAIL first, with name only as a tiebreak. A row that matches nothing
   * is reported as unmatched — never guessed at.
   */
  public record ImportRequest(@NotBlank String csv) {}

  public record ImportRow(
      String pin,
      String canonicalPin,
      String name,
      String email,
      /** CREATED, ALREADY_MAPPED, CONFLICT, UNMATCHED, SKIPPED_DELETED or INVALID_PIN. */
      String outcome,
      String detail,
      String matchedBy,
      String employeeId) {}

  public record ImportResult(
      boolean committed,
      int total,
      int created,
      int alreadyMapped,
      int conflicts,
      int unmatched,
      int skipped,
      List<ImportRow> rows) {}

  /** One disagreement between the terminal's own enrolment record and the seed CSV. */
  public record EnrolmentMismatch(
      String pin, String deviceName, String csvName, String email, String note) {}

  public record CrossCheckResult(
      int devicePinsChecked, int matched, int mismatched, int notInCsv, List<EnrolmentMismatch> mismatches) {}

  // ---------------------------------------------------------------- punches

  public record PunchView(
      String id,
      String employeeId,
      String employeeName,
      String devicePin,
      String deviceId,
      String area,
      String direction,
      Instant effectiveAt,
      LocalDate shiftDate,
      int burstCount,
      Instant burstFirstAt,
      Instant burstLastAt,
      String anomaly) {}

  public record SweepResult(int scanned, int promoted, int skipped, List<String> byOutcome) {}

  // -------------------------------------------------------- building policy

  /**
   * The "exceeding break" thresholds for one building.
   *
   * <p>Bean validation MIRRORS the V46 check constraints, per the standing rule: Hibernate's
   * {@code ddl-auto: validate} verifies tables, columns and types but never CHECKs, so a constraint on
   * its own surfaces as a {@code DataIntegrityViolationException} — a 500 the operator cannot act on.
   * The cross-field rule (max &gt; min) has no annotation form, so the service states it and returns a
   * sentence rather than a stack trace.
   */
  public record SitePolicyRequest(
      @jakarta.validation.constraints.NotNull
          @jakarta.validation.constraints.Min(value = 5, message = "Alert after must be at least 5 minutes")
          @jakarta.validation.constraints.Max(value = 1440, message = "Alert after must be under a day")
          Integer breakAlertMin,
      @jakarta.validation.constraints.NotNull
          @jakarta.validation.constraints.Min(value = 6, message = "Stop-treating-as-break must be at least 6 minutes")
          @jakarta.validation.constraints.Max(value = 1440, message = "Stop-treating-as-break must be under a day")
          Integer breakAlertMaxMin) {}
}
