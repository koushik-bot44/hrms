package com.ihrms.iclock;

import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockSiteCompanyRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.dto.IclockRosterDtos.PersonView;
import com.ihrms.iclock.dto.IclockRosterDtos.UpsertPersonRequest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The roster: import, IHRMS linking, and re-resolution.
 *
 * <p>This is the identity-first half of P1b. The biometric system owns {@code iclock_people}; an
 * IHRMS employee link is deferred enrichment. Nothing here requires an employee to exist.
 */
@Service
public class IclockRosterService {

  private static final Logger log = LoggerFactory.getLogger(IclockRosterService.class);

  /**
   * Seed company labels that do not match an IHRMS company by name, confirmed against real data.
   *
   * <p>Case-insensitive matching alone resolves almost nothing: the seed uses short trading names
   * while IHRMS holds full legal names ("Combino IT" vs "Combino Information Technologies"). An
   * EXPLICIT map is used rather than prefix or fuzzy matching, because a prefix rule silently picks
   * the wrong company the first time two share one, and 121 of 210 seed rows depend on this.
   */
  private static final Map<String, String> COMPANY_ALIASES =
      Map.of(
          "combino it", "Combino Information Technologies",
          "charm info", "Charm Info Systems",
          "screatives", "Screatives Software Services");

  private final IclockPersonRepository people;
  private final IclockSiteRepository sites;
  private final IclockPunchRepository punches;
  private final IclockRawPunchRepository rawPunches;
  private final IclockDeviceRepository devices;
  private final EmployeeRepository employees;
  private final CompanyRepository companies;
  private final IclockSiteCompanyRepository siteCompanies;
  private final IclockPromotionService promotion;
  private final IclockInboxService inbox;

  public IclockRosterService(
      IclockPersonRepository people,
      IclockSiteRepository sites,
      IclockPunchRepository punches,
      IclockRawPunchRepository rawPunches,
      IclockDeviceRepository devices,
      EmployeeRepository employees,
      CompanyRepository companies,
      IclockSiteCompanyRepository siteCompanies,
      IclockPromotionService promotion,
      IclockInboxService inbox) {
    this.people = people;
    this.sites = sites;
    this.punches = punches;
    this.rawPunches = rawPunches;
    this.devices = devices;
    this.employees = employees;
    this.companies = companies;
    this.siteCompanies = siteCompanies;
    this.promotion = promotion;
    this.inbox = inbox;
  }

  // ------------------------------------------------------------- roster CRUD

  @Transactional(readOnly = true)
  public List<PersonView> listPeople(String siteId) {
    requireSite(siteId);
    java.util.Set<String> dupes = new java.util.HashSet<>(people.findDuplicateEmails(siteId));
    return people.findBySiteIdOrderByPinAsc(siteId).stream().map(p -> view(p, dupes)).toList();
  }

  /**
   * Creates or updates a person by {@code (siteId, pin)} — the console's "assign this pin" flow,
   * which turns an inbox entry into a roster row in two clicks.
   */
  @Transactional
  public PersonView upsertPerson(String siteId, UpsertPersonRequest req) {
    requireSite(siteId);
    String pin = IclockPin.canonicalOrNull(req.pin());
    if (pin == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "'" + req.pin() + "' is not a usable pin (digits only, not all zeros)");
    }
    IclockPerson person = people.findBySiteIdAndPin(siteId, pin).orElseGet(IclockPerson::new);
    person.setSiteId(siteId);
    person.setPin(pin);
    apply(person, req);
    return view(people.save(person), java.util.Set.of());
  }

  @Transactional
  public PersonView editPerson(String personId, UpsertPersonRequest req) {
    IclockPerson person = requirePerson(personId);
    if (req.pin() != null && !req.pin().isBlank()) {
      String pin = IclockPin.canonicalOrNull(req.pin());
      if (pin == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Not a usable pin");
      }
      people
          .findBySiteIdAndPin(person.getSiteId(), pin)
          .filter(other -> !other.getId().equals(personId))
          .ifPresent(
              other -> {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pin " + pin + " already belongs to another person at this site");
              });
      person.setPin(pin);
    }
    apply(person, req);
    return view(people.save(person), java.util.Set.of());
  }

  /** Removes the IHRMS link. Punch history is untouched — it belongs to the roster person. */
  @Transactional
  public PersonView unlink(String personId) {
    IclockPerson person = requirePerson(personId);
    person.setEmployeeId(null);
    return view(people.save(person), java.util.Set.of());
  }

  private void apply(IclockPerson person, UpsertPersonRequest req) {
    if (req.name() != null) person.setName(blankToNull(req.name()));
    if (req.email() != null) person.setEmail(normaliseEmail(req.email()));
    if (req.companyId() != null) person.setCompanyId(blankToNull(req.companyId()));
    if (req.companyLabel() != null) person.setCompanyLabel(blankToNull(req.companyLabel()));
    if (req.team() != null) person.setTeam(blankToNull(req.team()));
    if (req.role() != null) person.setRole(blankToNull(req.role()));
    if (req.lateExemptMin() != null) person.setLateExemptMin(Math.max(0, req.lateExemptMin()));
    if (req.active() != null) person.setActive(req.active());
    if (req.excludedFromReports() != null) person.setExcludedFromReports(req.excludedFromReports());
  }

  PersonView view(IclockPerson p, java.util.Set<String> duplicateEmails) {
    Company c = p.getCompanyId() == null ? null : companies.findById(p.getCompanyId()).orElse(null);
    Employee e = p.getEmployeeId() == null ? null : employees.findById(p.getEmployeeId()).orElse(null);
    boolean dup =
        p.getEmail() != null && duplicateEmails.contains(p.getEmail().toLowerCase(Locale.ROOT));
    return new PersonView(
        p.getId(), p.getSiteId(), p.getPin(), p.getName(), p.getEmail(),
        p.getCompanyId(), c == null ? null : c.getName(), p.getCompanyLabel(),
        p.getTeam(), p.getRole(), p.getLateExemptMin(), p.isActive(), p.isExcludedFromReports(),
        p.getEmployeeId(), e == null ? null : e.getFullName(),
        dup, p.getName() == null || p.getName().isBlank(),
        punches.countByPersonId(p.getId()));
  }

  // ----------------------------------------------------------------- import

  public record ImportRow(
      String pin, String name, String email, String companyLabel, String outcome, String detail) {}

  /** People grouped by resolved company — the People screen's cleanup queue on day one. */
  public record CompanyTally(String companyId, String companyName, String seedLabel, int people) {}

  public record ImportReport(
      boolean committed,
      int total,
      int created,
      int updated,
      int skipped,
      int companiesMatched,
      int companiesUnmatched,
      List<String> unmatchedCompanyLabels,
      List<String> duplicateEmails,
      /** Per-company person counts, resolved companies first. */
      List<CompanyTally> byCompany,
      /**
       * People carrying NO company label at all — they resolve and punch normally, but nothing can
       * scope them to a company, so they need an operator decision. Listed explicitly rather than
       * counted, because a count alone gives the operator nothing to act on.
       */
      List<ImportRow> companyless,
      List<ImportRow> rows) {}

  /**
   * What the roster import WOULD do. Writes nothing, and cannot.
   *
   * <p><b>{@code readOnly = true} is the safety, not the {@code commit} flag.</b> A preview that is
   * merely careful not to call {@code save()} is not safe: this runs inside a transaction, repository
   * lookups return MANAGED entities, and Hibernate flushes dirty ones at commit whether or not anyone
   * asked it to. That is exactly how a "dry run writes nothing" contract turned into a silent rewrite
   * of every person in the roster. A read-only transaction puts Hibernate in {@code FlushMode.MANUAL}
   * and marks the JDBC connection read-only, so a write here fails loudly instead of happening
   * quietly — the preview is structurally incapable of the bug, not just currently innocent of it.
   *
   * <p>Deliberately a SEPARATE entry point rather than a flag on one method. Spring's transaction
   * advice is proxy-based, so a dispatcher calling {@code this.preview(...)} would run with the
   * caller's read-write transaction and silently lose the guarantee. Callers pick the method.
   */
  @Transactional(readOnly = true)
  public ImportReport previewRosterImport(String siteId, String csv) {
    return runRosterImport(siteId, csv, false);
  }

  /** Applies the roster import, upserting by {@code (siteId, pin)}. */
  @Transactional
  public ImportReport applyRosterImport(String siteId, String csv) {
    return runRosterImport(siteId, csv, true);
  }

  /**
   * Imports the seed roster, upserting by {@code (siteId, pin)}.
   *
   * <p>Company labels are resolved through the alias map then by exact case-insensitive name; an
   * unresolved label is STORED VERBATIM and reported, never dropped — "this company exists on the
   * terminals but not in IHRMS" is information the operator needs.
   */
  private ImportReport runRosterImport(String siteId, String csv, boolean commit) {
    requireSite(siteId);
    Map<String, Company> byName = companyIndexFor(siteId);

    List<ImportRow> report = new ArrayList<>();
    List<String> unmatchedLabels = new ArrayList<>();
    int created = 0, updated = 0, skipped = 0, matched = 0, unmatched = 0;

    for (String line : csv.split("\r\n|\n|\r")) {
      if (line.isBlank()) {
        continue;
      }
      String[] c = splitCsv(line);
      if (c.length < 4 || "pin".equalsIgnoreCase(c[0].trim())) {
        continue; // header, or too short to carry pin/name/email
      }
      String rawPin = c[0].trim();
      String canonical = IclockPin.canonicalOrNull(c[1].isBlank() ? rawPin : c[1].trim());
      String name = blankToNull(c[2]);
      String email = normaliseEmail(c[3]);
      String companyLabel = c.length > 4 ? blankToNull(c[4]) : null;
      String team = c.length > 5 ? blankToNull(c[5]) : null;
      String role = c.length > 6 ? blankToNull(c[6]) : null;
      int lateExempt = c.length > 7 ? parseIntOrZero(c[7]) : 0;
      boolean deleted = c.length > 8 && !c[8].trim().isBlank();
      // Optional 11th column; absent means false, so existing seed rows are unaffected.
      boolean excluded = c.length > 10 && isTruthy(c[10]);

      if (canonical == null) {
        skipped++;
        report.add(new ImportRow(rawPin, name, email, companyLabel, "INVALID_PIN", "not a usable pin"));
        continue;
      }

      Company company = null;
      if (companyLabel != null) {
        String key = companyLabel.trim().toLowerCase(Locale.ROOT);
        String aliased = COMPANY_ALIASES.getOrDefault(key, companyLabel.trim());
        company = byName.get(aliased.toLowerCase(Locale.ROOT));
        if (company == null) {
          unmatched++;
          if (!unmatchedLabels.contains(companyLabel)) {
            unmatchedLabels.add(companyLabel);
          }
        } else {
          matched++;
        }
      }

      Optional<IclockPerson> existing = people.findBySiteIdAndPin(siteId, canonical);
      boolean isNew = existing.isEmpty();

      // THE MUTATION IS INSIDE THE commit GUARD, and that placement is the whole safety of a dry run.
      //
      // This method is @Transactional, and findBySiteIdAndPin returns a MANAGED entity. Setting fields
      // on it and then deciding not to call save() does NOT undo anything: Hibernate dirty-checks the
      // persistence context and flushes at commit, so a "dry run" over an already-populated roster
      // silently rewrote every person it touched — renaming them, re-companying them, and applying the
      // CSV's deleted flag, all while reporting WOULD_UPDATE and writing nothing on purpose.
      //
      // It went unnoticed because the obvious test dry-runs against an EMPTY table, where every row is
      // new and the entity is never managed. The regression test for this seeds the roster first.
      if (commit) {
        IclockPerson person = existing.orElseGet(IclockPerson::new);
        person.setSiteId(siteId);
        person.setPin(canonical);
        person.setName(name);
        person.setEmail(email);
        person.setCompanyId(company == null ? null : company.getId());
        person.setCompanyLabel(companyLabel);
        person.setTeam(team);
        person.setRole(role);
        person.setLateExemptMin(lateExempt);
        person.setActive(!deleted);
        person.setExcludedFromReports(excluded);
        people.save(person);
      }
      if (isNew) {
        created++;
      } else {
        updated++;
      }
      report.add(
          new ImportRow(
              rawPin, name, email, companyLabel,
              commit ? (isNew ? "CREATED" : "UPDATED") : (isNew ? "WOULD_CREATE" : "WOULD_UPDATE"),
              company == null && companyLabel != null ? "company label unmatched — stored verbatim" : null));
    }

    List<String> dupes = commit ? people.findDuplicateEmails(siteId) : List.of();

    // Per-company tallies, keyed on the SEED label so an unmatched label still gets its own row —
    // otherwise "Combino IT" would vanish from the breakdown precisely because it failed to resolve.
    Map<String, CompanyTally> tallies = new java.util.LinkedHashMap<>();
    List<ImportRow> companyless = new ArrayList<>();
    for (ImportRow r : report) {
      if (r.outcome().startsWith("INVALID")) {
        continue;
      }
      if (r.companyLabel() == null) {
        companyless.add(r);
        continue;
      }
      String label = r.companyLabel().trim();
      String aliased = COMPANY_ALIASES.getOrDefault(label.toLowerCase(Locale.ROOT), label);
      Company resolved = byName.get(aliased.toLowerCase(Locale.ROOT));
      // Group by the RESOLVED company when there is one, so the seed's case-variant labels
      // ("Screatives" and "screatives") collapse into a single line the operator can act on.
      // Fall back to the label only when nothing resolved — an unmatched company still deserves
      // its own row rather than disappearing from the breakdown.
      String key = resolved != null ? "id:" + resolved.getId() : "label:" + label.toLowerCase(Locale.ROOT);
      CompanyTally prev = tallies.get(key);
      String seedLabels =
          prev == null
              ? label
              : (prev.seedLabel().contains(label) ? prev.seedLabel() : prev.seedLabel() + " / " + label);
      tallies.put(
          key,
          new CompanyTally(
              resolved == null ? null : resolved.getId(),
              resolved == null ? null : resolved.getName(),
              seedLabels,
              (prev == null ? 0 : prev.people()) + 1));
    }
    List<CompanyTally> byCompany =
        tallies.values().stream()
            .sorted((a, b) -> Integer.compare(b.people(), a.people()))
            .toList();

    return new ImportReport(
        commit, report.size(), created, updated, skipped, matched, unmatched,
        unmatchedLabels, dupes, byCompany, companyless, report);
  }

  /**
   * Company name -> company, with the SITE's own companies winning any name collision.
   *
   * <p>Production genuinely contains two companies whose names differ only in case — "Spire Info
   * Tech" and "Spire info tech" — and the lookup key is lowercased, so they collide. A plain
   * {@code putIfAbsent} over {@code findAll()} therefore resolved the collision by whatever order the
   * database happened to return rows in: the seed's 13 Spire people could be filed under either
   * company from one import to the next, and the losing one is not even linked to this site.
   *
   * <p>Site-linked companies are indexed FIRST, so they always win. That is not just a tiebreak, it
   * is the right answer: the import is scoped to a site, and the companies working at that site are
   * known. A company outside the site can still match when nothing at the site does — it is real
   * information that the label exists in IHRMS — but it can never displace one that is actually here.
   */
  private Map<String, Company> companyIndexFor(String siteId) {
    Set<String> linked =
        siteCompanies.findBySiteId(siteId).stream()
            .map(sc -> sc.getCompanyId())
            .collect(java.util.stream.Collectors.toSet());
    List<Company> all = companies.findAll();
    Map<String, Company> byName = new HashMap<>();
    for (Company c : all) {
      if (linked.contains(c.getId())) {
        byName.put(c.getName().trim().toLowerCase(Locale.ROOT), c);
      }
    }
    for (Company c : all) {
      byName.putIfAbsent(c.getName().trim().toLowerCase(Locale.ROOT), c);
    }
    return byName;
  }

  // ---------------------------------------------------------------- linking

  public record LinkSuggestion(
      String employeeId, String employeeName, String employeeEmail, String employeeStatus,
      String confidence, String basis) {}

  /**
   * Suggests IHRMS employees a roster person might be.
   *
   * <p>HIGH = exact email match. MEDIUM = normalised full-name match within the person's resolved
   * company. Nothing is auto-linked: a confident-looking wrong suggestion is worse than none, so the
   * operator confirms every one.
   */
  @Transactional(readOnly = true)
  public List<LinkSuggestion> suggestionsFor(String personId) {
    IclockPerson person = requirePerson(personId);
    List<LinkSuggestion> out = new ArrayList<>();

    if (person.getEmail() != null && !person.getEmail().isBlank()) {
      for (Employee e : employees.findAllByEmailIgnoreCase(person.getEmail())) {
        if (people.findByEmployeeId(e.getId()).isEmpty()) {
          out.add(suggestion(e, "HIGH", "email matches exactly"));
        }
      }
    }
    if (out.isEmpty() && person.getName() != null && person.getCompanyId() != null) {
      String target = normaliseName(person.getName());
      for (Employee e : employees.findByCompanyId(person.getCompanyId())) {
        if (e.getFullName() == null || !people.findByEmployeeId(e.getId()).isEmpty()) {
          continue;
        }
        if (normaliseName(e.getFullName()).equals(target)) {
          out.add(suggestion(e, "MEDIUM", "name matches within the same company"));
        }
      }
    }
    return out;
  }

  /**
   * Confirms a link and retro-fills that person's existing effective punches.
   *
   * <p>Idempotent: the update is a plain assignment of the same values, so re-running confirms the
   * same state and changes nothing. Scoped to ONE person's punches, so it is safe to run while live
   * ingest is writing for everyone else.
   */
  @Transactional
  public PersonView confirmLink(String personId, String employeeId) {
    IclockPerson person = requirePerson(personId);
    Employee employee =
        employees
            .findById(employeeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Employee not found"));
    people
        .findByEmployeeId(employeeId)
        .filter(p -> !p.getId().equals(personId))
        .ifPresent(
            p -> {
              throw new ResponseStatusException(
                  HttpStatus.CONFLICT, "That employee is already linked to roster person " + p.getId());
            });

    person.setEmployeeId(employee.getId());
    if (person.getCompanyId() == null) {
      person.setCompanyId(employee.getCompanyId());
    }
    people.save(person);

    int retro = 0;
    for (IclockPunch p : punches.findAll()) {
      if (personId.equals(p.getPersonId())
          && (p.getEmployeeId() == null || p.getCompanyId() == null)) {
        p.setEmployeeId(employee.getId());
        if (p.getCompanyId() == null) {
          p.setCompanyId(person.getCompanyId());
        }
        punches.save(p);
        retro++;
      }
    }
    log.info("iclock: linked person {} -> employee {}, retro-filled {} punches", personId, employeeId, retro);
    return view(person, java.util.Set.of());
  }

  /**
   * The inbox for a site, windowed at the earliest device claim.
   *
   * <p>The window instant is derived here rather than passed in, so the console cannot accidentally
   * ask for a different one and get a different answer than the re-resolution would act on.
   */
  @Transactional(readOnly = true)
  public com.ihrms.iclock.dto.IclockAdminDtos.UnmappedInbox inboxFor(String siteId, int limit) {
    requireSite(siteId);
    Instant since =
        devices.findBySiteId(siteId).stream()
            .map(IclockDevice::getClaimedAt)
            .filter(java.util.Objects::nonNull)
            .min(Instant::compareTo)
            .orElse(Instant.now()); // nothing claimed yet: everything is archive, nothing actionable
    return inbox.unmapped(siteId, since, limit);
  }

  // -------------------------------------------------------- re-resolution

  public record ReresolveReport(int scanned, int promoted, int alreadyDone, Instant since) {}

  /**
   * Scoped re-resolution: re-attempts promotion for raw punches received since a device was CLAIMED.
   *
   * <p>This is what makes the roster import retroactive without touching the archive. Punches that
   * arrived after {@code claimedAt} were declined only because no roster existed yet; once it does,
   * they promote. Deliberately bounded by claimedAt rather than sweeping all history — the archive
   * predates adoption and re-deriving it is a separate, explicit decision.
   *
   * <p>Idempotent via {@code iclock_punch_members.rawPunchId}: an already-promoted punch is a no-op.
   */
  @Transactional
  public ReresolveReport reresolveSinceClaim(String siteId, int limit) {
    requireSite(siteId);
    Instant earliestClaim =
        devices.findBySiteId(siteId).stream()
            .map(IclockDevice::getClaimedAt)
            .filter(java.util.Objects::nonNull)
            .min(Instant::compareTo)
            .orElseThrow(
                () -> new ResponseStatusException(
                    HttpStatus.CONFLICT, "No claimed device at this site — nothing to re-resolve"));

    int scanned = 0, promoted = 0, already = 0;
    for (IclockRawPunch raw : rawPunches.findUnpromotedSince(earliestClaim, limit)) {
      scanned++;
      IclockPromotionService.Outcome outcome = promotion.promote(raw.getId());
      if (outcome.promoted()) {
        promoted++;
      } else if (outcome == IclockPromotionService.Outcome.ALREADY_PROMOTED) {
        already++;
      }
    }
    return new ReresolveReport(scanned, promoted, already, earliestClaim);
  }

  // ------------------------------------------------------------------ util

  private IclockPerson requirePerson(String personId) {
    return people
        .findById(personId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Person not found"));
  }

  private void requireSite(String siteId) {
    if (sites.findById(siteId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Site not found");
    }
  }

  private LinkSuggestion suggestion(Employee e, String confidence, String basis) {
    return new LinkSuggestion(
        e.getId(), e.getFullName(), e.getEmail(), String.valueOf(e.getStatus()), confidence, basis);
  }

  /**
   * Name normalisation for MEDIUM-confidence matching: case-folded, punctuation stripped, whitespace
   * runs collapsed. Deliberately does NOT reorder words — "Kumar Raj" and "Raj Kumar" are different
   * people often enough that treating them as one would produce exactly the confident-looking wrong
   * suggestion this is meant to avoid.
   */
  static String normaliseName(String name) {
    if (name == null) {
      return "";
    }
    return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
  }

  static String normaliseEmail(String email) {
    if (email == null) {
      return null;
    }
    String e = email.trim().replaceAll("['\"]+$", "");
    return e.isEmpty() ? null : e;
  }

  private static String blankToNull(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim();
    return t.isEmpty() ? null : t;
  }

  /** Accepts the usual spellings an operator or a spreadsheet might produce. */
  private static boolean isTruthy(String s) {
    if (s == null) return false;
    String v = s.trim().toLowerCase(Locale.ROOT);
    return v.equals("true") || v.equals("yes") || v.equals("y") || v.equals("1");
  }

  private static int parseIntOrZero(String s) {
    try {
      return Integer.parseInt(s.trim());
    } catch (Exception e) {
      return 0;
    }
  }

  /** Minimal CSV split honouring double-quoted fields — names in the seed contain commas. */
  static String[] splitCsv(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cur = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        quoted = !quoted;
      } else if (ch == ',' && !quoted) {
        out.add(cur.toString());
        cur.setLength(0);
      } else {
        cur.append(ch);
      }
    }
    out.add(cur.toString());
    return out.toArray(new String[0]);
  }
}
