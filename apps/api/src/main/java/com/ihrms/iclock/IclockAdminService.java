package com.ihrms.iclock;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockEmployeePin;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.model.IclockSiteCompany;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockEmployeePinRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteCompanyRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.dto.IclockAdminDtos.AssignPinRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.ClaimDeviceRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.CreateSiteRequest;
import com.ihrms.iclock.dto.IclockAdminDtos.DeviceView;
import com.ihrms.iclock.dto.IclockAdminDtos.ImportResult;
import com.ihrms.iclock.dto.IclockAdminDtos.ImportRow;
import com.ihrms.iclock.dto.IclockAdminDtos.PinView;
import com.ihrms.iclock.dto.IclockAdminDtos.SiteCompanyView;
import com.ihrms.iclock.dto.IclockAdminDtos.SiteDetailView;
import com.ihrms.iclock.dto.IclockAdminDtos.SiteView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The iClock administration surface: sites, company links, device claiming and PIN mapping.
 *
 * <p>Refusals are {@link ResponseStatusException}, matching {@code AuthorizationService} — the global
 * advice renders those as the standard error envelope, whereas an {@code AccessDeniedException}
 * thrown here would surface as a 500 because no handler exists for it.
 */
@Service
public class IclockAdminService {

  private static final Logger log = LoggerFactory.getLogger(IclockAdminService.class);

  /** Serials reserved for probes — structurally unclaimable, so a test pin can never own real data. */
  private static final Pattern RESERVED_SERIAL = Pattern.compile("^ZZTEST", Pattern.CASE_INSENSITIVE);

  private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$");

  private final IclockSiteRepository sites;
  private final IclockSiteCompanyRepository siteCompanies;
  private final IclockDeviceRepository devices;
  private final IclockEmployeePinRepository pins;
  private final IclockRawPunchRepository rawPunches;
  private final EmployeeRepository employees;
  private final CompanyRepository companies;

  public IclockAdminService(
      IclockSiteRepository sites,
      IclockSiteCompanyRepository siteCompanies,
      IclockDeviceRepository devices,
      IclockEmployeePinRepository pins,
      IclockRawPunchRepository rawPunches,
      EmployeeRepository employees,
      CompanyRepository companies) {
    this.sites = sites;
    this.siteCompanies = siteCompanies;
    this.devices = devices;
    this.pins = pins;
    this.rawPunches = rawPunches;
    this.employees = employees;
    this.companies = companies;
  }

  // ------------------------------------------------------------------ sites

  @Transactional
  public SiteView createSite(CreateSiteRequest req) {
    String name = req.name().trim();
    if (sites.findByNameIgnoreCase(name).isPresent()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "A site named '" + name + "' already exists");
    }
    String tz = (req.timezone() == null || req.timezone().isBlank())
        ? ShiftConfig.ZONE.getId()
        : req.timezone().trim();
    if (!ShiftConfig.ZONE.getId().equals(tz)) {
      // ShiftConfig.ZONE is a single global constant that shift-day attribution and the late threshold
      // are computed in. A second timezone would fork that silently, so refuse until it is a parameter.
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Only " + ShiftConfig.ZONE.getId() + " is supported until shift configuration is per-site");
    }
    IclockSite site = new IclockSite();
    site.setName(name);
    site.setTimezone(tz);
    return view(sites.saveAndFlush(site));
  }

  @Transactional(readOnly = true)
  public List<SiteView> listSites() {
    return sites.findAllByOrderByNameAsc().stream().map(this::view).toList();
  }

  @Transactional
  public SiteView renameSite(String siteId, String name) {
    IclockSite site = site(siteId);
    String trimmed = name.trim();
    sites.findByNameIgnoreCase(trimmed)
        .filter(other -> !other.getId().equals(siteId))
        .ifPresent(other -> {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "That name is taken");
        });
    site.setName(trimmed);
    return view(sites.saveAndFlush(site));
  }

  @Transactional(readOnly = true)
  public SiteDetailView siteDetail(String siteId) {
    IclockSite site = site(siteId);
    List<SiteCompanyView> linked =
        siteCompanies.findBySiteId(siteId).stream()
            .map(sc -> companies.findById(sc.getCompanyId())
                .map(c -> new SiteCompanyView(c.getId(), c.getName(), c.getSlug(), isArchived(c)))
                // Surfaced even if the company row vanished, rather than silently dropped.
                .orElse(new SiteCompanyView(sc.getCompanyId(), "(unknown)", null, true)))
            .toList();
    List<DeviceView> claimed =
        devices.findAll().stream()
            .filter(d -> siteId.equals(d.getSiteId()))
            .map(d -> view(d, site.getName()))
            .toList();
    return new SiteDetailView(view(site), linked, claimed);
  }

  /**
   * Links a company to a site and re-points that company's existing pins in the SAME transaction.
   *
   * <p>D2 makes this a real constraint rather than a report: {@code UNIQUE(companyId)} refuses a second
   * site, and re-pointing the pins can violate {@code UNIQUE(siteId, pin)} — which is the intended,
   * loud outcome. A pin collision created by merging two namespaces surfaces here as a 409 instead of
   * silently shadowing somebody at the gate.
   */
  @Transactional
  public SiteDetailView linkCompany(String siteId, String companyId) {
    IclockSite site = site(siteId);
    companies.findById(companyId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

    Optional<IclockSiteCompany> existing = siteCompanies.findByCompanyId(companyId);
    if (existing.isPresent()) {
      if (existing.get().getSiteId().equals(siteId)) {
        return siteDetail(siteId); // idempotent
      }
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "That company is already linked to another site. Unlink it first — a company belongs to exactly one site.");
    }
    IclockSiteCompany link = new IclockSiteCompany();
    link.setSiteId(siteId);
    link.setCompanyId(companyId);
    siteCompanies.saveAndFlush(link);

    int repointed = pins.repointCompanyPins(companyId, siteId);
    if (repointed > 0) {
      log.info("iclock: re-pointed {} pins of company {} to site {}", repointed, companyId, site.getId());
    }
    return siteDetail(siteId);
  }

  @Transactional
  public void unlinkCompany(String siteId, String companyId) {
    IclockSiteCompany link = siteCompanies.findByCompanyId(companyId)
        .filter(sc -> sc.getSiteId().equals(siteId))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "That company is not linked to this site"));
    long pinCount = employees.findByCompanyId(companyId).stream()
        .filter(e -> pins.findByEmployeeId(e.getId()).isPresent())
        .count();
    if (pinCount > 0) {
      // The pins' denormalised siteId would become a lie, and nothing would re-derive it.
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "That company still has " + pinCount + " mapped pin(s). Remove them before unlinking.");
    }
    siteCompanies.delete(link);
  }

  // ---------------------------------------------------------------- devices

  @Transactional(readOnly = true)
  public List<DeviceView> listDevices(String status) {
    // Newest contact first, per the recency addendum — and deterministic, which findAll() alone was
    // not: it returned database row order, so two calls could disagree for no reason. A terminal that
    // has never called in sorts last rather than first; "never seen" is not "just seen".
    return devices.findAll().stream()
        .filter(d -> status == null || status.equalsIgnoreCase(d.getStatus()))
        .sorted(
            java.util.Comparator.comparing(
                    IclockDevice::getLastSeenAt,
                    java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder()))
                .thenComparing(IclockDevice::getSerialNumber))
        .map(d -> view(d, siteName(d.getSiteId())))
        .toList();
  }

  @Transactional
  public DeviceView claimDevice(String deviceId, ClaimDeviceRequest req) {
    IclockDevice device = devices.findById(deviceId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    if (RESERVED_SERIAL.matcher(device.getSerialNumber()).find()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Serial " + device.getSerialNumber() + " is reserved for probes and cannot be claimed");
    }
    IclockSite site = site(req.siteId());
    device.setSiteId(site.getId());
    device.setArea(req.area());
    device.setDirection(req.direction());
    device.setClaimedAt(Instant.now());
    device.setStatus("CLAIMED");
    if (req.name() != null && !req.name().isBlank()) {
      device.setName(req.name().trim());
    }
    return view(devices.saveAndFlush(device), site.getName());
  }

  @Transactional
  public DeviceView unclaimDevice(String deviceId) {
    IclockDevice device = devices.findById(deviceId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Device not found"));
    // Existing punches keep their snapshotted site/area/direction; only future ones stop promoting.
    device.setStatus("UNCLAIMED");
    device.setSiteId(null);
    device.setArea(null);
    device.setDirection(null);
    device.setClaimedAt(null);
    return view(devices.saveAndFlush(device), null);
  }

  // ------------------------------------------------------------------- pins

  @Transactional(readOnly = true)
  public List<PinView> listPins(String siteId) {
    site(siteId);
    return pins.findBySiteIdOrderByPinAsc(siteId).stream().map(this::view).toList();
  }

  @Transactional
  public PinView assignPin(String siteId, AssignPinRequest req) {
    IclockSite site = site(siteId);
    String pin = IclockPin.canonicalOrNull(req.pin());
    if (pin == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "'" + req.pin() + "' is not a usable pin (digits only, not all zeros)");
    }
    Employee employee = employees.findById(req.employeeId())
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));

    IclockSiteCompany link = siteCompanies.findByCompanyId(employee.getCompanyId())
        .orElseThrow(() -> new ResponseStatusException(
            HttpStatus.CONFLICT, "That employee's company is not linked to any site"));
    if (!link.getSiteId().equals(site.getId())) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "That employee belongs to a company linked to a different site");
    }
    pins.findByEmployeeId(employee.getId()).ifPresent(p -> {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "That employee already has pin " + p.getPin());
    });
    pins.findBySiteIdAndPin(site.getId(), pin).ifPresent(p -> {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Pin " + pin + " is already mapped at this site to employee " + p.getEmployeeId());
    });

    IclockEmployeePin row = new IclockEmployeePin();
    row.setEmployeeId(employee.getId());
    row.setPin(pin);
    row.setSiteId(site.getId());
    return view(pins.saveAndFlush(row));
  }

  @Transactional
  public void deletePin(String pinId) {
    IclockEmployeePin row = pins.findById(pinId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pin mapping not found"));
    pins.delete(row);
  }

  // ----------------------------------------------------------------- import

  /**
   * Bulk PIN import from the merged seed CSV.
   *
   * <p>Employees are matched by EMAIL first; name is used only to break a tie when one address maps to
   * several employees — which the real seed data does contain. Nothing is ever guessed: a row that
   * cannot be resolved to exactly one employee is reported and skipped.
   *
   * <p>Dry-run by default. {@code commit=true} performs the writes.
   */
  /**
   * What the pin import WOULD do. Writes nothing, and cannot — see
   * {@code IclockRosterService.previewRosterImport} for why {@code readOnly = true} is the safety
   * rather than the {@code commit} flag.
   *
   * <p>This path happens to construct only NEW entities inside its commit guard, so it never had the
   * dirty-checking bug its sibling did. That is a property of how it is written today, not a property
   * anything enforces — one future edit that adjusts a loaded row would reintroduce it silently. The
   * read-only transaction makes it structural.
   */
  @Transactional(readOnly = true)
  public ImportResult previewPinImport(String siteId, String csv) {
    return runPinImport(siteId, csv, false);
  }

  /** Applies the pin import. */
  @Transactional
  public ImportResult applyPinImport(String siteId, String csv) {
    return runPinImport(siteId, csv, true);
  }

  private ImportResult runPinImport(String siteId, String csv, boolean commit) {
    IclockSite site = site(siteId);
    List<ImportRow> report = new ArrayList<>();
    int created = 0, already = 0, conflicts = 0, unmatched = 0, skipped = 0;

    for (String line : csv.split("\r\n|\n|\r")) {
      if (line.isBlank()) {
        continue;
      }
      String[] c = line.split(",", -1);
      if (c.length < 4 || c[0].trim().equalsIgnoreCase("pin")) {
        continue; // header or a line too short to carry pin+name+email
      }
      String rawPin = c[0].trim();
      String canonical = IclockPin.canonicalOrNull(c[1].isBlank() ? rawPin : c[1].trim());
      String name = c[2].trim();
      String email = c[3].trim().replaceAll("['\"]+$", "");
      String deleted = c.length > 8 ? c[8].trim() : "";

      if (!deleted.isBlank()) {
        skipped++;
        report.add(new ImportRow(rawPin, canonical, name, email, "SKIPPED_DELETED", "marked deleted in the seed", null, null));
        continue;
      }
      if (canonical == null) {
        skipped++;
        report.add(new ImportRow(rawPin, null, name, email, "INVALID_PIN", "not a usable pin", null, null));
        continue;
      }
      if (!EMAIL.matcher(email).matches()) {
        unmatched++;
        report.add(new ImportRow(rawPin, canonical, name, email, "UNMATCHED",
            "email is malformed or missing — fix the seed row, it is not guessed at", null, null));
        continue;
      }

      List<Employee> byEmail = employees.findAllByEmailIgnoreCase(email);
      Employee match;
      String matchedBy;
      if (byEmail.isEmpty()) {
        unmatched++;
        report.add(new ImportRow(rawPin, canonical, name, email, "UNMATCHED", "no employee with that email", null, null));
        continue;
      } else if (byEmail.size() == 1) {
        match = byEmail.get(0);
        matchedBy = "email";
      } else {
        // Name is a TIEBREAK only, never a primary key.
        List<Employee> byName = byEmail.stream()
            .filter(e -> e.getFullName() != null && e.getFullName().trim().equalsIgnoreCase(name))
            .toList();
        if (byName.size() != 1) {
          conflicts++;
          report.add(new ImportRow(rawPin, canonical, name, email, "CONFLICT",
              byEmail.size() + " employees share that email and the name did not resolve it", null, null));
          continue;
        }
        match = byName.get(0);
        matchedBy = "email+name";
      }

      Optional<IclockSiteCompany> link = siteCompanies.findByCompanyId(match.getCompanyId());
      if (link.isEmpty() || !link.get().getSiteId().equals(site.getId())) {
        conflicts++;
        report.add(new ImportRow(rawPin, canonical, name, email, "CONFLICT",
            "that employee's company is not linked to this site", matchedBy, match.getId()));
        continue;
      }

      Optional<IclockEmployeePin> mine = pins.findByEmployeeId(match.getId());
      if (mine.isPresent()) {
        if (mine.get().getPin().equals(canonical)) {
          already++;
          report.add(new ImportRow(rawPin, canonical, name, email, "ALREADY_MAPPED", "unchanged", matchedBy, match.getId()));
        } else {
          conflicts++;
          report.add(new ImportRow(rawPin, canonical, name, email, "CONFLICT",
              "already mapped to pin " + mine.get().getPin(), matchedBy, match.getId()));
        }
        continue;
      }
      Optional<IclockEmployeePin> taken = pins.findBySiteIdAndPin(site.getId(), canonical);
      if (taken.isPresent()) {
        conflicts++;
        report.add(new ImportRow(rawPin, canonical, name, email, "CONFLICT",
            "pin already mapped at this site to employee " + taken.get().getEmployeeId(), matchedBy, match.getId()));
        continue;
      }

      if (commit) {
        IclockEmployeePin row = new IclockEmployeePin();
        row.setEmployeeId(match.getId());
        row.setPin(canonical);
        row.setSiteId(site.getId());
        pins.saveAndFlush(row);
      }
      created++;
      report.add(new ImportRow(rawPin, canonical, name, email,
          commit ? "CREATED" : "WOULD_CREATE", null, matchedBy, match.getId()));
    }
    return new ImportResult(commit, report.size(), created, already, conflicts, unmatched, skipped, report);
  }

  // ------------------------------------------------------------------ views

  private IclockSite site(String siteId) {
    return sites.findById(siteId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found"));
  }

  private String siteName(String siteId) {
    return siteId == null ? null : sites.findById(siteId).map(IclockSite::getName).orElse(null);
  }

  /**
   * Archived companies are SURFACED with a flag rather than filtered out, matching how the hierarchy
   * views treat them — an operator needs to see that a linked company is archived, not have it quietly
   * vanish from the site.
   */
  private static boolean isArchived(Company c) {
    return c.getStatus() != null && "DELETED".equals(String.valueOf(c.getStatus()));
  }

  private SiteView view(IclockSite s) {
    return new SiteView(
        s.getId(), s.getName(), s.getTimezone(),
        siteCompanies.countBySiteId(s.getId()),
        devices.findAll().stream().filter(d -> s.getId().equals(d.getSiteId())).count(),
        pins.countBySiteId(s.getId()),
        s.getCreatedAt());
  }

  private DeviceView view(IclockDevice d, String siteName) {
    return new DeviceView(
        d.getId(), d.getSerialNumber(), d.getName(), d.getStatus(), d.getSiteId(), siteName,
        d.getArea(), d.getDirection(), d.getLastSeenAt(), d.getLastHandshakeAt(), d.getClaimedAt(),
        d.getFirmwareInfo(), rawPunches.countBySerialNumber(d.getSerialNumber()));
  }

  private PinView view(IclockEmployeePin p) {
    Employee e = employees.findById(p.getEmployeeId()).orElse(null);
    Company c = e == null ? null : companies.findById(e.getCompanyId()).orElse(null);
    return new PinView(
        p.getId(), p.getPin(), p.getEmployeeId(),
        e == null ? null : e.getFullName(),
        e == null ? null : e.getEmployeeCode(),
        e == null ? null : e.getCompanyId(),
        c == null ? null : c.getName(),
        e == null ? null : String.valueOf(e.getStatus()));
  }

  static String normaliseEmail(String email) {
    return email == null ? null : email.trim().replaceAll("['\"]+$", "").toLowerCase(Locale.ROOT);
  }
}
