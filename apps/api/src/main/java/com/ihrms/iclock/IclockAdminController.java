package com.ihrms.iclock;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.iclock.dto.IclockAdminDtos.AssignPinRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.ClaimDeviceRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.CreateSiteRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.DeviceView;
import com.ihrms.iclock.dto.IclockAdminDtos.ImportRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.ImportResult;
import com.ihrms.iclock.dto.IclockAdminDtos.LinkCompanyRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.PinView;
import com.ihrms.iclock.dto.IclockAdminDtos.RenameSiteRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.SiteDetailView;
import com.ihrms.iclock.dto.IclockAdminDtos.SiteView;
import com.ihrms.iclock.dto.IclockAdminDtos.SweepResult;
import com.ihrms.iclock.dto.IclockAdminDtos;
import com.ihrms.iclock.dto.IclockRosterDtos.AssignPersonRequest;
import com.ihrms.iclock.dto.IclockRosterDtos.PersonView;
import com.ihrms.iclock.dto.IclockRosterDtos.AssignShiftReport;
import com.ihrms.iclock.dto.IclockRosterDtos.AssignShiftRequest;
import com.ihrms.iclock.dto.IclockRosterDtos.AssignShiftRow;
import com.ihrms.iclock.dto.IclockRosterDtos.BulkDeactivateReport;
import com.ihrms.iclock.dto.IclockRosterDtos.BulkDeactivateRequest;
import com.ihrms.iclock.dto.IclockRosterDtos.BulkDeactivateRow;
import com.ihrms.iclock.dto.IclockRosterDtos.UpsertPersonRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * iClock administration — sites, device claiming, PIN mapping and the roster.
 *
 * <p><b>The path prefix is load-bearing, not cosmetic.</b> {@code /provisioning/**} is already
 * {@code hasRole("SUPER_ADMIN")} in the security chain, so this surface is fail-closed even if someone
 * forgets to add a rule. Mounting it anywhere under {@code /iclock/} would match the
 * {@code permitAll("/iclock/**")} device carve-out FIRST — authorization is first-match-wins — and
 * serve device-claiming and PIN writes with no JWT, no role and no principal, reachable from the
 * internet.
 *
 * <p>Living outside {@code /iclock/} also keeps admin JSON bodies — employee names, emails, pins — out
 * of {@code iclock_request_logs}, whose own javadoc calls it unencrypted and unretained, and keeps
 * these endpoints inside the normal audit interceptor rather than the device containment zone that
 * masks every failure as 200 OK.
 *
 * <p>{@code @PreAuthorize} is belt-and-braces alongside the matcher. The matcher is the one that
 * matters: a {@code @PreAuthorize} denial surfaces as a 500 here, because the global advice has a
 * catch-all {@code Exception} handler and no {@code AccessDeniedException} handler, whereas the
 * filter-chain path yields the proper 403 envelope.
 *
 * <p><b>Auditing.</b> Every mutation below records an explicit, named audit event. The interceptor
 * already logs {@code "POST /provisioning/iclock/sites/{id}/people/import"} with a status code, which
 * says an operator did <em>something</em> here — but not which site, which pin, whose identity, or how
 * many rows changed. These endpoints decide who every punch in the building belongs to, so the trail
 * has to answer "who changed this person's attribution, and to what" without a log dive.
 *
 * <p>The events are PLATFORM-scoped (no {@code companyId}), because a site spans many companies and a
 * terminal belongs to none. Partitioning a device claim under one arbitrary company would file it
 * where nobody looks and imply an ownership that does not exist. The affected company is carried in
 * the metadata instead. They are readable via the audit explorer's "Platform (no company)" scope.
 */
@RestController
@RequestMapping("/provisioning/iclock")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class IclockAdminController {

  private final IclockAdminService admin;
  private final IclockInboxService inbox;
  private final IclockRosterService roster;
  private final IclockBoardService board;
  private final IclockFeedService feed;
  private final IclockSitePolicyService policies;
  private final IclockReportService reports;
  private final IclockCommandService commands;
  private final IclockBiometricService biometrics;
  private final IclockRegisterAuditService registerAudits;
  private final IclockPersonRemovalService removals;
  private final AuditService audit;

  public IclockAdminController(
      IclockAdminService admin,
      IclockInboxService inbox,
      IclockRosterService roster,
      IclockBoardService board,
      IclockFeedService feed,
      IclockSitePolicyService policies,
      IclockReportService reports,
      IclockCommandService commands,
      IclockBiometricService biometrics,
      IclockRegisterAuditService registerAudits,
      IclockPersonRemovalService removals,
      AuditService audit) {
    this.admin = admin;
    this.inbox = inbox;
    this.roster = roster;
    this.board = board;
    this.feed = feed;
    this.policies = policies;
    this.reports = reports;
    this.commands = commands;
    this.biometrics = biometrics;
    this.registerAudits = registerAudits;
    this.removals = removals;
    this.audit = audit;
  }

  // ------------------------------------------------------------------ sites

  @PostMapping("/sites")
  public SiteView createSite(
      @Valid @RequestBody CreateSiteRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    SiteView site = admin.createSite(req);
    record(actor, http, "ICLOCK_SITE_CREATED", "IclockSite", site.id(),
        meta("name", site.name(), "timezone", site.timezone()));
    return site;
  }

  @GetMapping("/sites")
  public List<SiteView> listSites() {
    return admin.listSites();
  }

  @GetMapping("/sites/{siteId}")
  public SiteDetailView siteDetail(@PathVariable String siteId) {
    return admin.siteDetail(siteId);
  }

  @PatchMapping("/sites/{siteId}")
  public SiteView renameSite(
      @PathVariable String siteId,
      @Valid @RequestBody RenameSiteRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    SiteView site = admin.renameSite(siteId, req.name());
    record(actor, http, "ICLOCK_SITE_RENAMED", "IclockSite", siteId, meta("name", site.name()));
    return site;
  }

  /**
   * Links a company to a building. Idempotent.
   *
   * <p>A company may work in SEVERAL buildings since V49 — Screatives and Sphinix both do — so a link
   * elsewhere is no longer a conflict. Existing pins are left exactly where they are: a new link now
   * means the company has ADDED a building, not moved.
   */
  @PostMapping("/sites/{siteId}/companies")
  public SiteDetailView linkCompany(
      @PathVariable String siteId,
      @Valid @RequestBody LinkCompanyRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    SiteDetailView detail = admin.linkCompany(siteId, req.companyId());
    record(actor, http, "ICLOCK_COMPANY_LINKED", "IclockSite", siteId,
        meta("companyId", req.companyId()));
    return detail;
  }

  @DeleteMapping("/sites/{siteId}/companies/{companyId}")
  public void unlinkCompany(
      @PathVariable String siteId,
      @PathVariable String companyId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    admin.unlinkCompany(siteId, companyId);
    record(actor, http, "ICLOCK_COMPANY_UNLINKED", "IclockSite", siteId,
        meta("companyId", companyId));
  }

  // ---------------------------------------------------------------- devices

  /** Fleet roster. {@code ?status=UNCLAIMED} is the adoption queue. */
  @GetMapping("/devices")
  public List<DeviceView> listDevices(@RequestParam(required = false) String status) {
    return admin.listDevices(status);
  }

  @PostMapping("/devices/{deviceId}/claim")
  public DeviceView claim(
      @PathVariable String deviceId,
      @Valid @RequestBody ClaimDeviceRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    DeviceView device = admin.claimDevice(deviceId, req);
    // The serial is the identifier an operator standing next to the terminal can actually read off it.
    record(actor, http, "ICLOCK_DEVICE_CLAIMED", "IclockDevice", deviceId,
        meta("serialNumber", device.serialNumber(), "siteId", req.siteId(),
            "area", device.area(), "direction", device.direction()));
    return device;
  }

  @PostMapping("/devices/{deviceId}/unclaim")
  public DeviceView unclaim(
      @PathVariable String deviceId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    DeviceView device = admin.unclaimDevice(deviceId);
    // Unclaiming silently stops attribution while punches keep arriving, so it is the single most
    // important thing in this controller to be able to trace back to a person and a time.
    record(actor, http, "ICLOCK_DEVICE_UNCLAIMED", "IclockDevice", deviceId,
        meta("serialNumber", device.serialNumber()));
    return device;
  }

  /**
   * Changes a terminal's role. FORWARD-ONLY: attribution changes from this moment, and effective
   * punches already written keep the role they were promoted under.
   */
  @PatchMapping("/devices/{deviceId}/role")
  public DeviceView changeRole(
      @PathVariable String deviceId,
      @Valid @RequestBody IclockAdminDtos.ChangeRoleRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    DeviceView device = admin.changeDeviceRole(deviceId, req.area(), req.direction());
    record(actor, http, "ICLOCK_DEVICE_ROLE_CHANGED", "IclockDevice", deviceId,
        meta("serialNumber", device.serialNumber(), "area", device.area(),
            "direction", device.direction()));
    return device;
  }

  /** Moves a terminal to another building. FORWARD-ONLY; past punches keep their building. */
  @PatchMapping("/devices/{deviceId}/building")
  public DeviceView moveBuilding(
      @PathVariable String deviceId,
      @Valid @RequestBody IclockAdminDtos.MoveBuildingRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    DeviceView device = admin.moveDeviceToSite(deviceId, req.siteId());
    record(actor, http, "ICLOCK_DEVICE_MOVED", "IclockDevice", deviceId,
        meta("serialNumber", device.serialNumber(), "siteId", req.siteId(),
            "siteName", device.siteName()));
    return device;
  }

  // ------------------------------------------------------------------- pins

  @GetMapping("/sites/{siteId}/pins")
  public List<PinView> listPins(@PathVariable String siteId) {
    return admin.listPins(siteId);
  }

  @PostMapping("/sites/{siteId}/pins")
  public PinView assignPin(
      @PathVariable String siteId,
      @Valid @RequestBody AssignPinRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    PinView pin = admin.assignPin(siteId, req);
    record(actor, http, "ICLOCK_PIN_ASSIGNED", "IclockEmployeePin", pin.id(),
        meta("siteId", siteId, "pin", pin.pin(), "employeeId", pin.employeeId(),
            "companyId", pin.companyId()));
    return pin;
  }

  /**
   * Not nested under a site, deliberately: a pin belongs to an employee, and the site is derived from
   * that employee's company. The URL shape tells the truth about the data model.
   */
  @DeleteMapping("/pins/{pinId}")
  public void deletePin(
      @PathVariable String pinId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    admin.deletePin(pinId);
    record(actor, http, "ICLOCK_PIN_DELETED", "IclockEmployeePin", pinId, meta());
  }

  /**
   * Bulk import from the merged seed CSV. Matches employees by EMAIL, with name only as a tiebreak.
   *
   * <p>DRY RUN by default — it reports what it would do and writes nothing. Pass {@code ?commit=true}
   * to apply. Rows that cannot be resolved to exactly one employee are reported, never guessed at.
   */
  @PostMapping("/sites/{siteId}/pins/import")
  public ImportResult importPins(
      @PathVariable String siteId,
      @Valid @RequestBody ImportRequest req,
      @RequestParam(defaultValue = "false") boolean commit,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    ImportResult result =
        commit ? admin.applyPinImport(siteId, req.csv()) : admin.previewPinImport(siteId, req.csv());
    // Only the commit is audited. A dry run writes nothing, and auditing every preview would bury the
    // handful of rows that actually changed the system under the many that changed nothing.
    if (commit) {
      record(actor, http, "ICLOCK_PINS_IMPORTED", "IclockSite", siteId,
          meta("total", result.total(), "created", result.created(),
              "alreadyMapped", result.alreadyMapped(), "conflicts", result.conflicts(),
              "unmatched", result.unmatched(), "skipped", result.skipped()));
    }
    return result;
  }

  // ------------------------------------------------------------------ inbox

  /**
   * Pins that punched but resolve to nobody — the operator's daily queue, split into the live
   * action list and a collapsed archive count.
   */
  @GetMapping("/sites/{siteId}/inbox/unmapped")
  public IclockAdminDtos.UnmappedInbox unmapped(
      @PathVariable String siteId, @RequestParam(defaultValue = "500") int limit) {
    return roster.inboxFor(siteId, limit);
  }

  /**
   * "I fixed the cause, try again." Idempotent, and BOUNDED to punches received since the earliest
   * device claim.
   *
   * <p>Deliberately {@code sweepSinceClaim} and not {@code sweep}: the unbounded variant reaches the
   * whole pre-adoption archive oldest-first, which is backfill, and backfill is off by policy and
   * belongs to the one-shot flag-gated runner — not to an operator's button.
   */
  @PostMapping("/promote/sweep")
  public SweepResult sweep(
      @RequestParam(defaultValue = "5000") int limit,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    SweepResult result = inbox.sweepSinceClaim(limit);
    record(actor, http, "ICLOCK_SWEEP_RUN", null, null,
        meta("scanned", result.scanned(), "promoted", result.promoted(),
            "skipped", result.skipped(), "byOutcome", result.byOutcome()));
    return result;
  }

  // ----------------------------------------------------------------- roster

  /** The roster — the biometric system's own identity source. */
  @GetMapping("/sites/{siteId}/people")
  public List<PersonView> people(@PathVariable String siteId) {
    return roster.listPeople(siteId);
  }

  /** Create or edit one person. Used by the console's "assign this pin" two-click flow. */
  @PostMapping("/sites/{siteId}/people")
  public PersonView upsertPerson(
      @PathVariable String siteId,
      @Valid @RequestBody UpsertPersonRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    PersonView person = roster.createPerson(siteId, req);
    record(actor, http, "ICLOCK_PERSON_UPSERTED", "IclockPerson", person.id(),
        meta("siteId", siteId, "pin", person.pin(), "name", person.name(),
            "companyId", person.companyId()));
    return person;
  }

  @PatchMapping("/people/{personId}")
  public PersonView editPerson(
      @PathVariable String personId,
      @Valid @RequestBody UpsertPersonRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    PersonView person = roster.editPerson(personId, req);
    // active and excludedFromReports are recorded as RESULTING state rather than as a diff: the two
    // are routinely confused (one stops resolution, the other only stops reporting), and the trail
    // has to say unambiguously what the person was left as.
    //
    // Company and team are recorded too. Moving somebody between companies changes which report they
    // appear in and which company's admin is copied on their warning letter, so it is an attribution
    // change and belongs in the trail rather than being filed as a cosmetic edit.
    record(actor, http, "ICLOCK_PERSON_EDITED", "IclockPerson", personId,
        meta("pin", person.pin(), "name", person.name(), "active", person.active(),
            "excludedFromReports", person.excludedFromReports(),
            "companyId", person.companyId(), "companyName", person.companyName(),
            "team", person.team(), "duplicateEmail", person.duplicateEmail()));
    return person;
  }

  /**
   * Moves a group of people onto a shift — the People screen's bulk action.
   *
   * <p>Audited with the pins that MOVED rather than the whole selection, because the selection is what
   * was clicked and the moved set is what happened; a trail that cannot tell a real assignment from a
   * re-application of one is no use when somebody asks why a night turned up on the wrong day.
   */
  @PostMapping("/sites/{siteId}/people/shift-profile")
  public AssignShiftReport assignShift(
      @PathVariable String siteId,
      @Valid @RequestBody AssignShiftRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    AssignShiftReport report =
        roster.assignShift(siteId, req.personIds(), req.shiftProfile());
    record(actor, http, "ICLOCK_SHIFT_ASSIGNED", "IclockSite", siteId,
        meta("shiftProfile", report.shiftProfile(), "changed", report.changed(),
            "alreadyOnIt", report.alreadyOnIt(),
            "pins",
            report.rows().stream()
                .filter(r -> !java.util.Objects.equals(r.from(), r.to()))
                .map(AssignShiftRow::pin)
                .toList()));
    return report;
  }

  // ------------------------------------------------------- device commands (P3)

  /**
   * Pushes one person's name to every claimed terminal at their building.
   *
   * <p>EXPLICIT OPERATOR ACTION. Editing a person never queues this by itself: the console offers it
   * after a name change and somebody has to say yes. That separation is the whole safety story of the
   * command channel, and it is why this is a POST of its own rather than a side effect of the PATCH.
   */
  @PostMapping("/people/{personId}/push-name")
  public List<Map<String, Object>> pushName(
      @PathVariable String personId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var queued = commands.queueNameUpdate(personId, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_COMMAND_QUEUED", "IclockPerson", personId,
        meta("kind", "UPDATE_USERINFO", "devices", queued.size(),
            "pin", queued.isEmpty() ? null : queued.get(0).getDevicePin(),
            "commandIds", queued.stream().map(c -> c.getId()).toList(),
            "commandsEnabled", commands.enabled()));
    return queued.stream().map(c -> meta(
        "id", c.getId(), "deviceId", c.getDeviceId(), "status", c.getStatus(),
        "payload", c.getPayload())).toList();
  }

  /** Sets a terminal's clock from the server, in that building's timezone. */
  @PostMapping("/devices/{deviceId}/sync-time")
  public Map<String, Object> syncTime(
      @PathVariable String deviceId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var c = commands.queueTimeSync(deviceId, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_COMMAND_QUEUED", "IclockDevice", deviceId,
        meta("kind", "SET_TIME", "commandId", c.getId(), "payload", c.getPayload(),
            "commandsEnabled", commands.enabled()));
    return meta("id", c.getId(), "status", c.getStatus(), "payload", c.getPayload());
  }

  /**
   * Removes an enrolment from a terminal. Guarded: refused while the pin belongs to an ACTIVE person.
   *
   * <p>Irreversible from here — the person has to physically re-enrol — which is why the guard lives
   * in the service and not merely in the UI.
   */
  @PostMapping("/devices/{deviceId}/delete-user")
  public Map<String, Object> deleteUserOnDevice(
      @PathVariable String deviceId,
      @RequestParam String pin,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var c = commands.queueUserDelete(deviceId, pin, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_COMMAND_QUEUED", "IclockDevice", deviceId,
        meta("kind", "DELETE_USER", "pin", c.getDevicePin(), "commandId", c.getId(),
            "commandsEnabled", commands.enabled()));
    return meta("id", c.getId(), "status", c.getStatus(), "payload", c.getPayload());
  }

  /**
   * Puts one terminal into fingerprint capture mode for one person.
   *
   * <p>Queues two commands in order — the user row, then the capture trigger — because a template
   * attaches to a user the device already holds. The response says plainly that an acknowledgement
   * is not an enrolment: the terminal can answer {@code Return=0} for "understood" and still capture
   * nothing, since the outcome depends on a person standing at it.
   */
  @PostMapping("/people/{personId}/enrol")
  public Map<String, Object> enrolOnDevice(
      @PathVariable String personId,
      @RequestParam String deviceId,
      @RequestParam(defaultValue = "1") int type,
      @RequestParam(defaultValue = "0") int finger,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var t = commands.queueEnrolment(
        personId, deviceId, type, finger, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_ENROLMENT_QUEUED", "IclockPerson", personId,
        meta("kind", t.bioType() == 2 ? "ENROLL_BIO" : "ENROLL_FP",
            "deviceId", deviceId, "type", t.bioType(), "finger", t.fingerIndex(),
            "commandIds", t.queued().stream().map(c -> c.getId()).toList(),
            "commandsEnabled", commands.enabled()));
    return meta(
        "deviceName", t.deviceName(),
        "personName", t.personName(),
        "bioType", t.bioType(),
        "finger", t.fingerIndex(),
        "finishAtTerminal", t.finishAtTerminal(),
        "commandIds", t.queued().stream().map(c -> c.getId()).toList(),
        "payload", t.queued().get(t.queued().size() - 1).getPayload());
  }

  /**
   * Asks a terminal for its own user table, which arrives back through the ordinary USERINFO ingest.
   *
   * <p>Read-only on the device. This is what gives the enrolment-seeding path a live input: the fleet
   * has never volunteered a USERINFO push, so we ask for one.
   */
  @PostMapping("/devices/{deviceId}/query-users")
  public Map<String, Object> queryUsers(
      @PathVariable String deviceId,
      @RequestParam(required = false) String pin,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var c = commands.queueUserQuery(deviceId, pin, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_COMMAND_QUEUED", "IclockDevice", deviceId,
        meta("kind", "QUERY_USERINFO", "pin", c.getDevicePin(), "commandId", c.getId(),
            "commandsEnabled", commands.enabled()));
    return meta("id", c.getId(), "status", c.getStatus(), "payload", c.getPayload());
  }

  /**
   * Where this person's fingerprints are, terminal by terminal.
   *
   * <p>The console's "enrolled on 4 of 4", and when it is not 4 of 4, which door is the problem.
   */
  @GetMapping("/people/{personId}/biometrics")
  public IclockBiometricService.EnrolmentState biometrics(@PathVariable String personId) {
    return biometrics.stateFor(personId);
  }

  /**
   * Re-pushes every finger this person has to every terminal in their building.
   *
   * <p>The repair action. Propagation already happens by itself when somebody enrols, so pressing
   * this means something went wrong — a terminal offline at the time, a full queue, a swapped
   * device. Deliberately unconditional: it does not skip terminals that claim to hold the finger
   * already, because the reason somebody is here is that the claim looks wrong.
   */
  @PostMapping("/people/{personId}/biometrics/resync")
  public Map<String, Object> resyncBiometrics(
      @PathVariable String personId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    int queued = biometrics.resync(personId, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_BIOMETRIC_RESYNC", "IclockPerson", personId,
        meta("kind", "UPDATE_FINGERTMP", "commandsQueued", queued,
            "commandsEnabled", commands.enabled()));
    return meta("commandsQueued", queued);
  }

  /**
   * Asks a terminal for its register, so it can be compared against the roster.
   *
   * <p>One terminal at a time: a single dump was 10 MB across 635 requests, and the terminals doing
   * it are the ones people are queueing at.
   */
  @PostMapping("/devices/{deviceId}/register-audit")
  public Map<String, Object> requestRegisterAudit(
      @PathVariable String deviceId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var audit = registerAudits.request(deviceId, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_REGISTER_AUDIT_REQUESTED", "IclockDevice", deviceId,
        meta("auditId", audit.getId(), "commandId", audit.getCommandId(),
            "commandsEnabled", commands.enabled()));
    return meta("auditId", audit.getId(), "status", audit.getStatus());
  }

  /** The newest audit for a terminal, grouped into its four evidence-backed findings. */
  @GetMapping("/devices/{deviceId}/register-audit")
  public IclockRegisterAuditService.AuditResult latestRegisterAudit(@PathVariable String deviceId) {
    return registerAudits.latestFor(deviceId).orElse(null);
  }

  /**
   * Removes selected pins from ONE terminal's register.
   *
   * <p>Registers only — no roster row and no punch is touched. The active-person guard still stands
   * underneath, so a pin belonging to somebody who works here is refused whatever was selected.
   */
  @PostMapping("/devices/{deviceId}/register-audit/delete")
  public Map<String, Object> deleteFromRegister(
      @PathVariable String deviceId,
      @RequestBody List<String> pins,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var result = registerAudits.deleteFromRegister(
        deviceId, pins, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_REGISTER_CLEANUP", "IclockDevice", deviceId,
        meta("kind", "DELETE_USER", "pins", pins, "commandsQueued", result.queued(),
            "refused", result.refused(), "commandsEnabled", commands.enabled()));
    return meta("commandsQueued", result.queued(), "refused", result.refused());
  }

  /**
   * What is on this terminal, flat and opinion-free.
   *
   * <p>The plain inventory, as distinct from the audit: no roster comparison, no groups. The
   * question "what is on this machine" deserves an answer that is not a diff.
   */
  @GetMapping("/devices/{deviceId}/register")
  public IclockRegisterAuditService.RegisterListing listRegister(@PathVariable String deviceId) {
    return registerAudits.listRegister(deviceId);
  }

  /** What removing this person would do. Queues nothing, writes nothing. */
  @PostMapping("/people/{personId}/remove/preview")
  public IclockPersonRemovalService.RemovalReport previewRemoveOne(@PathVariable String personId) {
    return removals.previewOne(personId);
  }

  /**
   * Removes one person from the terminals AND the roster.
   *
   * <p>Both halves in one action, because doing them separately is how the register and the roster
   * drift apart. Punches are untouched.
   */
  @PostMapping("/people/{personId}/remove")
  public IclockPersonRemovalService.RemovalReport removeOne(
      @PathVariable String personId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var report = removals.removeOne(personId, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_PERSON_REMOVED", "IclockPerson", personId,
        meta("rosterOutcome", report.rows().isEmpty() ? null : report.rows().get(0).rosterOutcome(),
            "pin", report.rows().isEmpty() ? null : report.rows().get(0).pin(),
            "terminals", report.commandsQueued(),
            "commandsEnabled", commands.enabled()));
    return report;
  }

  /** What removing a selection would do. */
  @PostMapping("/sites/{siteId}/people/remove/preview")
  public IclockPersonRemovalService.RemovalReport previewRemove(
      @PathVariable String siteId, @RequestBody List<String> personIds) {
    return removals.preview(siteId, personIds);
  }

  /** Removes a selection from the terminals and the roster. Building-bounded. */
  @PostMapping("/sites/{siteId}/people/remove")
  public IclockPersonRemovalService.RemovalReport removeMany(
      @PathVariable String siteId,
      @RequestBody List<String> personIds,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var report = removals.remove(siteId, personIds, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_PEOPLE_REMOVED", "IclockSite", siteId,
        meta("people", report.people(), "deleted", report.toDelete(),
            "deactivated", report.toDeactivate(), "commandsQueued", report.commandsQueued(),
            "recentlyActive", report.recentlyActive(),
            "pins", report.rows().stream().map(r -> r.pin()).toList(),
            "commandsEnabled", commands.enabled()));
    return report;
  }

  /**
   * Roster-only removal — for people the terminals hold nothing for.
   *
   * <p>The audit screen's template-gap list. No register to clear, so no commands are queued.
   */
  @PostMapping("/sites/{siteId}/people/remove/roster-only")
  public IclockPersonRemovalService.RemovalReport removeRosterOnly(
      @PathVariable String siteId,
      @RequestBody List<String> personIds,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    var report = removals.removeFromRosterOnly(
        siteId, personIds, actor == null ? null : actor.id());
    record(actor, http, "ICLOCK_PEOPLE_REMOVED_ROSTER_ONLY", "IclockSite", siteId,
        meta("people", report.people(), "deleted", report.toDelete(),
            "deactivated", report.toDeactivate(), "recentlyActive", report.recentlyActive(),
            "pins", report.rows().stream().map(r -> r.pin()).toList()));
    return report;
  }

  /** The command log for one terminal, newest first. */
  @GetMapping("/devices/{deviceId}/commands")
  public List<Map<String, Object>> commandLog(
      @PathVariable String deviceId,
      @RequestParam(defaultValue = "50") int limit) {
    return commands.logFor(deviceId, limit).stream().map(c -> meta(
        "id", c.getId(), "kind", c.getKind(), "status", c.getStatus(),
        "payload", c.getPayload(), "pin", c.getDevicePin(),
        "serveCount", c.getServeCount(), "ackReturn", c.getAckReturn(),
        "failureReason", c.getFailureReason(), "createdBy", c.getCreatedBy(),
        "createdAt", c.getCreatedAt(), "sentAt", c.getSentAt(),
        "completedAt", c.getCompletedAt())).toList();
  }

  /** Whether the channel may serve at all — so the console can say so rather than imply it. */
  @GetMapping("/commands/status")
  public Map<String, Object> commandStatus() {
    var out = commands.outstanding();
    return meta("enabled", commands.enabled(), "outstanding", out.size());
  }

  /**
   * What a bulk deactivation would do. Writes nothing — a separate entry point, not a flag.
   */
  @PostMapping("/sites/{siteId}/people/deactivate/preview")
  public BulkDeactivateReport previewBulkDeactivate(
      @PathVariable String siteId,
      @Valid @RequestBody BulkDeactivateRequest req) {
    return roster.previewBulkDeactivate(siteId, req.pins());
  }

  /**
   * Deactivates a list of pins at one building. Deactivate only; nothing here deletes.
   *
   * <p>ONE audit entry naming every pin that actually changed — not one per person, which would bury
   * the fact that a single operator action switched off sixty-four people. The reason is required and
   * recorded: a trail that says "64 deactivated" without saying why is barely a trail.
   */
  @PostMapping("/sites/{siteId}/people/deactivate")
  public BulkDeactivateReport bulkDeactivate(
      @PathVariable String siteId,
      @Valid @RequestBody BulkDeactivateRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    BulkDeactivateReport report =
        roster.bulkDeactivate(siteId, req.pins(), req.reason());
    record(actor, http, "ICLOCK_PEOPLE_DEACTIVATED", "IclockSite", siteId,
        meta("reason", req.reason(),
            "requested", report.requested(),
            "deactivated", report.deactivated(),
            "alreadyInactive", report.alreadyInactive(),
            "notOnRoster", report.notOnRoster(),
            "punchesRetained", report.punchesRetained(),
            "pins", report.rows().stream()
                .filter(r -> "DEACTIVATED".equals(r.outcome()))
                .map(BulkDeactivateRow::pin)
                .toList(),
            "alsoActiveElsewhere", report.rows().stream()
                .filter(BulkDeactivateRow::activeElsewhere)
                .map(BulkDeactivateRow::pin)
                .toList()));
    return report;
  }

  /**
   * Active IHRMS companies, for the console's company picker.
   *
   * <p>A CLOSED LIST on purpose. Company was previously free text on the edit form, which is how a
   * roster ends up with "Screatives", "screatives" and "Screatives Software Services" as three
   * companies — the import already carries an alias map to undo exactly that damage from the seed
   * data, and there is no reason to keep manufacturing it by hand.
   */
  @GetMapping("/companies")
  public List<IclockRosterService.CompanyOption> companyOptions() {
    return roster.activeCompanies();
  }

  /**
   * Team labels already in use at a building.
   *
   * <p>Open by design, unlike companies: teams are labels the operator invents as the floor
   * reorganises, so a new one is legitimate. Offering what already exists is what stops one team
   * becoming three by typo.
   */
  @GetMapping("/sites/{siteId}/teams")
  public List<String> teamOptions(@PathVariable String siteId) {
    return roster.teamsAt(siteId);
  }

  /**
   * Bulk roster import. DRY RUN by default — reports what it would do, including per-company person
   * counts and every company-less row, and writes nothing. {@code ?commit=true} applies it.
   */
  @PostMapping("/sites/{siteId}/people/import")
  public IclockRosterService.ImportReport importRoster(
      @PathVariable String siteId,
      @Valid @RequestBody ImportRequest req,
      @RequestParam(defaultValue = "false") boolean commit,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    IclockRosterService.ImportReport report =
        commit
            ? roster.applyRosterImport(siteId, req.csv())
            : roster.previewRosterImport(siteId, req.csv());
    // Commits only — see importPins.
    if (commit) {
      record(actor, http, "ICLOCK_ROSTER_IMPORTED", "IclockSite", siteId,
          meta("total", report.total(), "created", report.created(), "updated", report.updated(),
              "skipped", report.skipped(), "companiesMatched", report.companiesMatched(),
              "companiesUnmatched", report.companiesUnmatched(),
              "companyless", report.companyless().size()));
    }
    return report;
  }

  /** IHRMS link suggestions for one roster person. Nothing is ever auto-linked. */
  @GetMapping("/people/{personId}/suggestions")
  public List<IclockRosterService.LinkSuggestion> suggestions(@PathVariable String personId) {
    return roster.suggestionsFor(personId);
  }

  /** Confirms a link and retro-fills that person's existing punches. Idempotent. */
  @PostMapping("/people/{personId}/link")
  public PersonView link(
      @PathVariable String personId,
      @Valid @RequestBody AssignPersonRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    PersonView person = roster.confirmLink(personId, req.employeeId());
    // This one retro-fills existing punches onto an IHRMS employee, so it rewrites history for that
    // employee's attendance. Both sides of the link go in the metadata.
    record(actor, http, "ICLOCK_PERSON_LINKED", "IclockPerson", personId,
        meta("pin", person.pin(), "employeeId", req.employeeId(),
            "employeeName", person.employeeName()));
    return person;
  }

  @DeleteMapping("/people/{personId}/link")
  public PersonView unlink(
      @PathVariable String personId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    PersonView person = roster.unlink(personId);
    record(actor, http, "ICLOCK_PERSON_UNLINKED", "IclockPerson", personId,
        meta("pin", person.pin()));
    return person;
  }

  /**
   * Scoped re-resolution: re-attempts promotion for raw punches received since the site's devices
   * were claimed. This is what makes a roster import retroactive without touching the archive.
   */
  @PostMapping("/sites/{siteId}/reresolve")
  public IclockRosterService.ReresolveReport reresolve(
      @PathVariable String siteId,
      @RequestParam(defaultValue = "20000") int limit,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    IclockRosterService.ReresolveReport report = roster.reresolveSinceClaim(siteId, limit);
    record(actor, http, "ICLOCK_RERESOLVED", "IclockSite", siteId,
        meta("scanned", report.scanned(), "promoted", report.promoted(),
            "alreadyDone", report.alreadyDone(),
            "since", report.since() == null ? null : report.since().toString()));
    return report;
  }

  // ------------------------------------------------------------ console reads

  /** Overview tiles for one site on the current shift-day. */
  @GetMapping("/sites/{siteId}/overview")
  public IclockBoardService.Overview overview(@PathVariable String siteId) {
    return board.overview(siteId);
  }

  /** Live Board: everyone bucketed by where they are right now. Polled by the console. */
  @GetMapping("/sites/{siteId}/board")
  public IclockBoardService.Board board(@PathVariable String siteId) {
    return board.board(siteId);
  }

  /** Person Day View: one person, one shift-day. Read-only in P1b. */
  @GetMapping("/people/{personId}/day")
  public IclockBoardService.PersonDay personDay(
      @PathVariable String personId, @RequestParam(required = false) String shiftDate) {
    return board.personDay(personId, shiftDate);
  }

  // ------------------------------------------------------------- punch feed

  /**
   * The raw punch feed — what the terminals actually sent, newest first.
   *
   * <p>{@code siteId} omitted means EVERY building, which is what a fleet view has to mean. Scoping is
   * a filter on the same query rather than a separate data path.
   *
   * <p>{@code shiftDate} omitted defaults to the current shift day: the archive is 34,000 rows and a
   * ticker that opens on all of history is not a ticker.
   */
  @GetMapping("/punch-feed")
  public IclockFeedService.Feed punchFeed(
      @RequestParam(required = false) String siteId,
      @RequestParam(required = false) String deviceId,
      @RequestParam(required = false) String shiftDate,
      @RequestParam(defaultValue = "ALL") IclockFeedService.Attribution attribution,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "100") int size) {
    java.time.LocalDate day = shiftDate == null || shiftDate.isBlank()
        ? null
        : java.time.LocalDate.parse(shiftDate);
    return feed.feed(siteId, deviceId, day, attribution, page, size);
  }

  // ------------------------------------------------ multi-building scoping

  /**
   * Live board across ONE building or ALL of them.
   *
   * <p>The nested {@code /sites/{siteId}/board} stays as the single-building form; this is the same
   * read with the scope as a filter, so a fleet view is not a second pipeline.
   */
  @GetMapping("/board")
  public IclockBoardService.Board boardScoped(@RequestParam(required = false) String siteId) {
    return board.boardAcross(siteId);
  }

  /** Overview across one building or all of them. Same scoping rule as the board. */
  @GetMapping("/overview")
  public IclockBoardService.Overview overviewScoped(@RequestParam(required = false) String siteId) {
    return board.overviewAcross(siteId);
  }

  // ---------------------------------------------------------------- reports

  /**
   * The month, for one building. Read-only, so no audit entry: reading a report is not a change, and
   * an audit trail that logs every screen view buries the entries that matter.
   *
   * @param period {@code yyyy-MM}, labelled by its END month per the ratified rule
   */
  @GetMapping("/sites/{siteId}/reports/monthly")
  public IclockReportService.MonthlyReport monthlyReport(
      @PathVariable String siteId, @RequestParam String period) {
    return reports.monthly(siteId, parsePeriod(period));
  }

  /**
   * The same month as a payroll CSV.
   *
   * <p>Formatted from the very same report object the screen renders, never recomputed — the CSV, the
   * screen and the warning letter disagreeing about somebody's LOP is exactly what made the
   * spreadsheet era untrustworthy.
   */
  @GetMapping(value = "/sites/{siteId}/reports/monthly.csv", produces = "text/csv")
  public ResponseEntity<String> monthlyCsv(
      @PathVariable String siteId,
      @RequestParam String period,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    IclockReportService.MonthlyReport report = reports.monthly(siteId, parsePeriod(period));
    // Audited, unlike the on-screen report: this one leaves the building.
    record(actor, http, "ICLOCK_PAYROLL_EXPORTED", "IclockSite", siteId,
        meta("period", period, "people", report.peopleReported(),
            "excluded", report.peopleExcluded()));
    return ResponseEntity.ok()
        .header("Content-Disposition",
            "attachment; filename=\"" + IclockPayrollExport.filename(report) + "\"")
        .body(IclockPayrollExport.toCsv(report));
  }

  /**
   * The warning letters this period WOULD produce. Composes only; nothing is sent from here.
   *
   * <p>Sending stays operator-triggered and is dark until the mail credentials are configured, so the
   * preview is the whole surface for now — which is the right order: nobody should be able to mail 200
   * people before somebody has read one of the letters.
   */
  @GetMapping("/sites/{siteId}/reports/warnings")
  public List<Map<String, Object>> warningPreview(
      @PathVariable String siteId, @RequestParam String period) {
    IclockReportService.MonthlyReport report = reports.monthly(siteId, parsePeriod(period));
    String label = parsePeriod(period).getMonth().getDisplayName(
        java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        + " " + parsePeriod(period).getYear();
    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (IclockReportService.PersonReport r : report.rows()) {
      if (!IclockWarningMail.warrantsWarning(r)) {
        continue;
      }
      IclockWarningMail.Letter letter = IclockWarningMail.compose(r, label);
      out.add(meta(
          "personId", r.personId(), "pin", r.pin(), "name", r.name(),
          "company", r.companyName(), "lateDays", letter.lateDays(),
          "lopDays", letter.lopDays(), "subject", letter.subject(), "body", letter.body()));
    }
    return out;
  }

  /** {@code 2026-08} to a YearMonth, with a 400 rather than a 500 when it is not. */
  private static java.time.YearMonth parsePeriod(String period) {
    try {
      return java.time.YearMonth.parse(period);
    } catch (Exception e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "Period must look like 2026-08.");
    }
  }

  // ---------------------------------------------------------- roster delete

  /** What deleting this person would do, or why it is refused. Read-only; drives the confirm dialog. */
  @GetMapping("/people/{personId}/delete-preflight")
  public IclockRosterService.DeletePreflight deletePreflight(@PathVariable String personId) {
    return roster.deletePreflight(personId);
  }

  /**
   * Hard-deletes a roster person. Refused with 409 unless they have NO punches and NO IHRMS link.
   *
   * <p>The audit entry carries the person's snapshot, captured before the row disappears — "a person
   * was deleted" with no name, pin or company is barely an audit entry.
   */
  @DeleteMapping("/people/{personId}")
  public IclockRosterService.DeletePreflight deletePerson(
      @PathVariable String personId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    IclockRosterService.DeletePreflight removed = roster.deletePerson(personId);
    record(actor, http, "ICLOCK_PERSON_DELETED", "IclockPerson", personId,
        meta("pin", removed.pin(), "name", removed.name(),
            "punchCount", removed.punchCount(), "wasLinked", removed.linkedToEmployee()));
    return removed;
  }

  // -------------------------------------------------------- building policy

  /** The building's "exceeding break" thresholds. Returns defaults when nothing is stored yet. */
  @GetMapping("/sites/{siteId}/policy")
  public IclockSitePolicyService.PolicyView sitePolicy(@PathVariable String siteId) {
    return policies.effective(siteId);
  }

  /**
   * Saves the building's break thresholds. Takes effect on the next board refresh — no redeploy.
   *
   * <p>Per building, which falls out free: the row is already site-scoped, so a second building gets
   * its own thresholds without further work.
   */
  @PutMapping("/sites/{siteId}/policy")
  public IclockSitePolicyService.PolicyView saveSitePolicy(
      @PathVariable String siteId,
      @Valid @RequestBody IclockAdminDtos.SitePolicyRequest req,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest http) {
    IclockSitePolicyService.PolicyView saved =
        policies.save(siteId, req.breakAlertMin(), req.breakAlertMaxMin());
    record(actor, http, "ICLOCK_SITE_POLICY_UPDATED", "IclockSite", siteId,
        meta("breakAlertMin", saved.breakAlertMin(), "breakAlertMaxMin", saved.breakAlertMaxMin()));
    return saved;
  }

  // ------------------------------------------------------------------ audit

  /**
   * Writes one iClock admin audit event.
   *
   * <p>The actor is passed through {@link AuditActor#from} unchanged, which for a SUPER_ADMIN yields a
   * null {@code companyId} — deliberately, see the class javadoc. {@link AuditService#record} never
   * throws, so no call site needs a try/catch and a failed audit write can never fail the mutation
   * that was already applied.
   */
  private void record(
      IhrmsPrincipal.User actor,
      HttpServletRequest http,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata) {
    audit.record(
        AuditActor.from(actor), action, targetType, targetId, metadata, http.getRemoteAddr());
  }

  /**
   * Builds an audit metadata map from alternating key/value pairs, DROPPING null values.
   *
   * <p>{@code Map.of} rejects nulls outright, and half of what is worth recording here is legitimately
   * null — an unnamed person, a company label that matched nothing, a site with no claimed device yet.
   * Throwing on those would turn "this person has no name" into a 500 on a mutation that already
   * succeeded. Insertion-ordered so the metadata reads in the order it was written.
   */
  private static Map<String, Object> meta(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) {
      Object value = pairs[i + 1];
      if (value != null) {
        map.put(String.valueOf(pairs[i]), value);
      }
    }
    return map;
  }
}
