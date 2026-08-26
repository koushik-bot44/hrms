package com.ihrms.iclock;

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
import com.ihrms.iclock.dto.IclockAdminDtos.UnmappedPinView;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * iClock administration — sites, device claiming and PIN mapping.
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
 */
@RestController
@RequestMapping("/provisioning/iclock")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class IclockAdminController {

  private final IclockAdminService admin;
  private final IclockInboxService inbox;

  public IclockAdminController(IclockAdminService admin, IclockInboxService inbox) {
    this.admin = admin;
    this.inbox = inbox;
  }

  // ------------------------------------------------------------------ sites

  @PostMapping("/sites")
  public SiteView createSite(@Valid @RequestBody CreateSiteRequest req) {
    return admin.createSite(req);
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
  public SiteView renameSite(@PathVariable String siteId, @Valid @RequestBody RenameSiteRequest req) {
    return admin.renameSite(siteId, req.name());
  }

  /**
   * Links a company to a site. Idempotent. A company belongs to exactly one site (D2), so linking one
   * that is already elsewhere is refused rather than silently moved — and any pin collision the link
   * would create surfaces here as a 409 instead of shadowing somebody at the gate.
   */
  @PostMapping("/sites/{siteId}/companies")
  public SiteDetailView linkCompany(
      @PathVariable String siteId, @Valid @RequestBody LinkCompanyRequest req) {
    return admin.linkCompany(siteId, req.companyId());
  }

  @DeleteMapping("/sites/{siteId}/companies/{companyId}")
  public void unlinkCompany(@PathVariable String siteId, @PathVariable String companyId) {
    admin.unlinkCompany(siteId, companyId);
  }

  // ---------------------------------------------------------------- devices

  /** Fleet roster. {@code ?status=UNCLAIMED} is the adoption queue. */
  @GetMapping("/devices")
  public List<DeviceView> listDevices(@RequestParam(required = false) String status) {
    return admin.listDevices(status);
  }

  @PostMapping("/devices/{deviceId}/claim")
  public DeviceView claim(@PathVariable String deviceId, @Valid @RequestBody ClaimDeviceRequest req) {
    return admin.claimDevice(deviceId, req);
  }

  @PostMapping("/devices/{deviceId}/unclaim")
  public DeviceView unclaim(@PathVariable String deviceId) {
    return admin.unclaimDevice(deviceId);
  }

  // ------------------------------------------------------------------- pins

  @GetMapping("/sites/{siteId}/pins")
  public List<PinView> listPins(@PathVariable String siteId) {
    return admin.listPins(siteId);
  }

  @PostMapping("/sites/{siteId}/pins")
  public PinView assignPin(@PathVariable String siteId, @Valid @RequestBody AssignPinRequest req) {
    return admin.assignPin(siteId, req);
  }

  /**
   * Not nested under a site, deliberately: a pin belongs to an employee, and the site is derived from
   * that employee's company. The URL shape tells the truth about the data model.
   */
  @DeleteMapping("/pins/{pinId}")
  public void deletePin(@PathVariable String pinId) {
    admin.deletePin(pinId);
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
      @RequestParam(defaultValue = "false") boolean commit) {
    return admin.importPins(siteId, req.csv(), commit);
  }

  // ------------------------------------------------------------------ inbox

  /** Pins that punched but resolve to nobody — the operator's daily queue. */
  @GetMapping("/inbox/unmapped")
  public List<UnmappedPinView> unmapped(@RequestParam(defaultValue = "200") int limit) {
    return inbox.unmapped(limit);
  }

  /** "I fixed the cause, try again." Idempotent. */
  @PostMapping("/promote/sweep")
  public SweepResult sweep(@RequestParam(defaultValue = "5000") int limit) {
    return inbox.sweep(limit);
  }
}
