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
  private final AuditService audit;

  public IclockAdminController(
      IclockAdminService admin,
      IclockInboxService inbox,
      IclockRosterService roster,
      IclockBoardService board,
      IclockFeedService feed,
      IclockSitePolicyService policies,
      AuditService audit) {
    this.admin = admin;
    this.inbox = inbox;
    this.roster = roster;
    this.board = board;
    this.feed = feed;
    this.policies = policies;
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
   * Links a company to a site. Idempotent. A company belongs to exactly one site (D2), so linking one
   * that is already elsewhere is refused rather than silently moved — and any pin collision the link
   * would create surfaces here as a 409 instead of shadowing somebody at the gate.
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
    PersonView person = roster.upsertPerson(siteId, req);
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
    record(actor, http, "ICLOCK_PERSON_EDITED", "IclockPerson", personId,
        meta("pin", person.pin(), "name", person.name(), "active", person.active(),
            "excludedFromReports", person.excludedFromReports()));
    return person;
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
