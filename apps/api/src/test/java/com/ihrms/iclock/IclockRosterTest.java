package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.model.IclockSiteCompany;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteCompanyRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.IclockRosterService.CompanyTally;
import com.ihrms.iclock.IclockRosterService.ImportReport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The roster: import (dry-run safety, company canonicalisation, the per-company breakdown) and the
 * partial edits the console makes on top of it.
 *
 * <p><b>The fixture is synthetic on purpose.</b> The real seed carries 206 people's names and email
 * addresses, and a CSV committed to git is permanent — a later deletion does not remove it from
 * history. So the repo gets a fabricated roster that exercises the same three behaviours the real one
 * does: case-variant labels for the same company collapsing into one tally, an alias resolving a
 * short terminal label to the full IHRMS company name, and rows with no company at all being listed
 * individually rather than counted.
 *
 * <p>{@code ICLOCK_SEED_CSV} points the last test at the real file when one is available locally; it
 * self-skips everywhere else, including CI. That keeps the production-shaped check runnable without
 * the data ever entering the repository.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockRosterTest {

  @Autowired IclockRosterService roster;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockPersonRepository people;
  @Autowired CompanyRepository companies;
  @Autowired IclockSiteCompanyRepository siteCompanies;
  @Autowired JdbcTemplate jdbc;

  private String siteId;

  /**
   * The five IHRMS companies the alias map is written against. Two of them are only reachable through
   * an alias ({@code "combino it"}, {@code "charm info"}); a third has a lowercase variant in the seed
   * that must NOT become a second company.
   */
  private static final List<String> COMPANY_NAMES =
      List.of(
          "Screatives Software Services",
          "Sphinix Technologies",
          "Combino Information Technologies",
          "Spire Info Tech",
          "Charm Info Systems");

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"iclock_punch_members\",\"iclock_punches\",\"iclock_raw_punches\","
            + "\"iclock_people\",\"iclock_employee_pins\",\"iclock_devices\","
            + "\"iclock_site_companies\",\"iclock_sites\",\"users\",\"employees\",\"companies\","
            + "\"teams\",\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");

    IclockSite site = new IclockSite();
    site.setName("Import Test Site");
    siteId = sites.save(site).getId();

    int i = 0;
    for (String name : COMPANY_NAMES) {
      Company c = new Company();
      c.setName(name);
      c.setCode("C" + (++i));
      companies.save(c);
    }
  }

  @Test
  void dryRunReportsEverythingAndWritesNothing() {
    ImportReport report = roster.previewRosterImport(siteId, fixture());

    assertThat(report.committed()).isFalse();
    assertThat(report.total()).isEqualTo(11);
    assertThat(report.created()).isEqualTo(11);
    assertThat(report.updated()).isZero();
    // The whole point of a dry run: the operator sees the outcome and the table is untouched.
    assertThat(people.findAll()).isEmpty();
    assertThat(report.rows()).allSatisfy(r -> assertThat(r.outcome()).startsWith("WOULD_"));
  }

  @Test
  void caseVariantsAndAliasesCollapseIntoOneCompanyEach() {
    ImportReport report = roster.previewRosterImport(siteId, fixture());

    assertThat(report.companiesUnmatched()).isZero();
    assertThat(report.unmatchedCompanyLabels()).isEmpty();

    // "Screatives" and "screatives" are ONE company, not two. If they split, the operator is shown a
    // breakdown that does not match the building and starts hunting a company that does not exist.
    CompanyTally screatives = tally(report, "Screatives Software Services");
    assertThat(screatives.people()).isEqualTo(3);
    assertThat(screatives.seedLabel()).contains("Screatives").contains("screatives");

    // The alias path: "Combino IT" is what the terminal roster calls it; the full name is what IHRMS
    // calls it. Both spellings must land on the same company id.
    CompanyTally combino = tally(report, "Combino Information Technologies");
    assertThat(combino.people()).isEqualTo(2);
    assertThat(combino.companyId()).isNotNull();

    CompanyTally charm = tally(report, "Charm Info Systems");
    assertThat(charm.people()).isEqualTo(2);

    assertThat(tally(report, "Sphinix Technologies").people()).isEqualTo(1);
    assertThat(tally(report, "Spire Info Tech").people()).isEqualTo(1);

    // Every row is accounted for exactly once: the per-company total plus the company-less rows.
    int inCompanies = report.byCompany().stream().mapToInt(CompanyTally::people).sum();
    assertThat(inCompanies + report.companyless().size()).isEqualTo(report.total());
  }

  /**
   * Two companies whose names differ only in case, one linked to the site and one not.
   *
   * <p>This is production's actual shape, not a hypothetical: "Spire Info Tech" (linked to Orion
   * Towers) and "Spire info tech" (not linked) both exist. The lookup key is lowercased, so they
   * collide, and resolving the collision by database row order meant the seed's Spire people could be
   * filed under a company that is not even at the site — differently on different runs.
   */
  @Test
  void aNameCollisionResolvesToTheCompanyLinkedToThisSite() {
    // The decoy is inserted BEFORE the real company, deliberately. The old code took the first row
    // findAll() returned for a given lowercased name, so with the decoy second the test would pass
    // whether or not the fix were present — proving nothing. Re-creating the real one afterwards puts
    // the decoy first, which is the case that used to go wrong.
    companies.delete(
        companies.findAll().stream()
            .filter(c -> "Spire Info Tech".equals(c.getName()))
            .findFirst()
            .orElseThrow());

    Company decoy = new Company();
    decoy.setName("spire info tech"); // same name, different case, NOT linked to the site
    decoy.setCode("DECOY");
    decoy = companies.save(decoy);

    Company real = new Company();
    real.setName("Spire Info Tech");
    real.setCode("SIT");
    real = companies.save(real);

    IclockSiteCompany link = new IclockSiteCompany();
    link.setSiteId(siteId);
    link.setCompanyId(real.getId());
    siteCompanies.save(link);

    // Guard the premise. If the driver ever stops returning these in insertion order the collision
    // stops being exercised, and this should say so rather than pass quietly.
    List<String> order =
        companies.findAll().stream()
            .map(Company::getName)
            .filter(n -> n.equalsIgnoreCase("Spire Info Tech"))
            .toList();
    assertThat(order)
        .as("the decoy must come first for this test to exercise anything")
        .containsExactly("spire info tech", "Spire Info Tech");

    ImportReport report = roster.applyRosterImport(siteId, fixture());

    CompanyTally spire = tally(report, "Spire Info Tech");
    assertThat(spire.companyId()).isEqualTo(real.getId());
    assertThat(spire.companyId()).isNotEqualTo(decoy.getId());
    assertThat(people.findBySiteIdAndPin(siteId, "906").orElseThrow().getCompanyId())
        .isEqualTo(real.getId());
  }

  @Test
  void companylessRowsAreListedIndividuallyNotJustCounted() {
    ImportReport report = roster.previewRosterImport(siteId, fixture());

    // A count alone gives the operator nothing to act on — each of these needs its own decision.
    assertThat(report.companyless()).hasSize(2);
    assertThat(report.companyless()).extracting(r -> r.pin()).containsExactlyInAnyOrder("901", "902");
  }

  @Test
  void unmatchedCompanyLabelIsStoredVerbatimRatherThanDropped() {
    String csv =
        header() + "\n" + row("910", "Nobody Ghost", "ghost@x.test", "Company That Left", "", "");

    ImportReport report = roster.applyRosterImport(siteId, csv);

    assertThat(report.companiesUnmatched()).isEqualTo(1);
    assertThat(report.unmatchedCompanyLabels()).containsExactly("Company That Left");
    // "This company exists on the terminals but not in IHRMS" is information. Dropping the label
    // would make the gap invisible and unfixable.
    var person = people.findBySiteIdAndPin(siteId, "910").orElseThrow();
    assertThat(person.getCompanyId()).isNull();
    assertThat(person.getCompanyLabel()).isEqualTo("Company That Left");
  }

  @Test
  void exclusionColumnIsOptionalAndOrthogonalToActive() {
    ImportReport report = roster.applyRosterImport(siteId, fixture());
    assertThat(report.committed()).isTrue();

    // Excluded from REPORTS, but still active — the two are different axes. The legacy tool's
    // "deleted" meant report-exclusion, and reading it as departure would erase a current employee
    // from the floor.
    var excluded = people.findBySiteIdAndPin(siteId, "905").orElseThrow();
    assertThat(excluded.isExcludedFromReports()).isTrue();
    assertThat(excluded.isActive()).isTrue();

    // The `deleted` column is the one that switches resolution off.
    var inactive = people.findBySiteIdAndPin(siteId, "906").orElseThrow();
    assertThat(inactive.isActive()).isFalse();
    assertThat(inactive.isExcludedFromReports()).isFalse();

    // A row with no 11th column at all defaults to false, so the pre-existing seed shape still works.
    var plain = people.findBySiteIdAndPin(siteId, "901").orElseThrow();
    assertThat(plain.getPin()).isEqualTo("901"); // unpadded in, unchanged out
    assertThat(plain.isExcludedFromReports()).isFalse();
  }

  @Test
  void reimportUpdatesInPlaceRatherThanDuplicating() {
    roster.applyRosterImport(siteId, fixture());
    long first = people.count();

    ImportReport again = roster.applyRosterImport(siteId, fixture());

    // Upsert is keyed on (siteId, pin) — running the import twice is a no-op, not 20 people.
    assertThat(again.created()).isZero();
    assertThat(again.updated()).isEqualTo(11);
    assertThat(people.count()).isEqualTo(first);
  }

  /**
   * The ADOPTION GATE: the real seed, dry-run, must reproduce the ratified breakdown exactly.
   *
   * <p>These numbers are not a snapshot of whatever the code happens to produce — they are the
   * distribution that was predicted from the seed, checked by hand, and ratified before any of it was
   * allowed to touch production. Encoding them here is what makes the gate re-runnable: if a change to
   * canonicalisation, the alias map or the tally keying moves a single person between companies, this
   * fails and names the company it moved to.
   *
   * <p>Skipped unless {@code ICLOCK_SEED_CSV} names a readable file. The seed carries 206 people's
   * names and email addresses and must never enter the repository, so the check lives here and the
   * data stays outside.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "ICLOCK_SEED_CSV", matches = ".+")
  void realSeedReproducesTheRatifiedBreakdown() throws Exception {
    String csv = Files.readString(Path.of(System.getenv("ICLOCK_SEED_CSV")));

    ImportReport report = roster.previewRosterImport(siteId, csv);

    assertThat(report.total()).isEqualTo(212);
    assertThat(report.created()).isEqualTo(212);
    assertThat(report.skipped()).isZero();

    // Every company label resolves. A single unmatched label here means a company exists on the
    // terminals that IHRMS has never heard of, and its people would import with no company scope.
    assertThat(report.companiesUnmatched()).isZero();
    assertThat(report.unmatchedCompanyLabels()).isEmpty();

    assertThat(tally(report, "Screatives Software Services").people()).isEqualTo(102);
    assertThat(tally(report, "Sphinix Technologies").people()).isEqualTo(38);
    assertThat(tally(report, "Combino Information Technologies").people()).isEqualTo(28);
    assertThat(tally(report, "Spire Info Tech").people()).isEqualTo(13);
    assertThat(tally(report, "Charm Info Systems").people()).isEqualTo(12);
    assertThat(report.byCompany()).hasSize(5);

    assertThat(report.companyless()).hasSize(19);

    // 102 + 38 + 28 + 13 + 12 + 19 = 212, with nothing counted twice and nothing dropped.
    int inCompanies = report.byCompany().stream().mapToInt(CompanyTally::people).sum();
    assertThat(inCompanies).isEqualTo(193);
    assertThat(inCompanies + report.companyless().size()).isEqualTo(212);

    assertThat(people.findAll()).isEmpty(); // still a dry run — nothing was written

    // ---- the two people the ruling reinstated -------------------------------------------------
    //
    // Current employees the legacy tool had flagged "deleted". They must import EXCLUDED FROM REPORTS
    // but ACTIVE: if they land inactive, two people who are in the building every day stop resolving
    // at the gate, and the failure is silent — their punches simply stay raw.
    //
    // This has to COMMIT to assert it. ImportRow carries neither `active` nor `excludedFromReports`,
    // so a dry run cannot observe the property at all; counting that the two pins appear in the
    // report — which is what this check used to do — passes for any values the seed happens to hold,
    // and named a guarantee it was not making.
    ImportReport applied = roster.applyRosterImport(siteId, csv);
    assertThat(applied.created()).isEqualTo(212);

    var bipul = people.findBySiteIdAndPin(siteId, "18292").orElseThrow();
    assertThat(bipul.isActive()).as("18292 must resolve at the gate").isTrue();
    assertThat(bipul.isExcludedFromReports()).as("18292 is excluded from REPORTS only").isTrue();

    var bheema = people.findBySiteIdAndPin(siteId, "17414").orElseThrow();
    assertThat(bheema.isActive()).as("17414 must resolve at the gate").isTrue();
    assertThat(bheema.isExcludedFromReports()).as("17414 is excluded from REPORTS only").isTrue();

    // Exactly two people are excluded, and exactly four are inactive — the seed's genuine deleted=YES
    // rows. Any drift in either number means the ruling was applied to the wrong people.
    assertThat(people.findAll().stream().filter(p -> p.isExcludedFromReports()).count()).isEqualTo(2);
    assertThat(people.findAll().stream().filter(p -> !p.isActive()).count()).isEqualTo(4);
    assertThat(people.count()).isEqualTo(212);
  }

  @Test
  void editingOneFieldLeavesEveryOtherFieldAlone() {
    roster.applyRosterImport(siteId, fixture());
    // Looked up as "1", not "001": the seed carries padded pins because that is what the fleet sends
    // ("000261", "02919"), and the import canonicalises them on the way in. The person is stored under
    // the canonical form, so anything reading them back has to use it too.
    var before = people.findBySiteIdAndPin(siteId, "1").orElseThrow();
    assertThat(before.getPin()).isEqualTo("1");
    assertThat(before.getName()).isEqualTo("Ada Placeholder");
    assertThat(before.getCompanyId()).isNotNull();

    // Exactly what the console's "Excluded from reports" toggle sends: the pin (required for
    // identification) and the one field being changed. If the update were a whole-object overwrite,
    // this single click would blank the person's name, email, company and team — a toggle that
    // quietly destroys the roster row it is attached to.
    roster.editPerson(
        before.getId(),
        new com.ihrms.iclock.dto.IclockRosterDtos.UpsertPersonRequest(
            before.getPin(), null, null, null, null, null, null, null, null, true, null));

    var after = people.findBySiteIdAndPin(siteId, "1").orElseThrow();
    assertThat(after.isExcludedFromReports()).isTrue();
    assertThat(after.getName()).isEqualTo("Ada Placeholder");
    assertThat(after.getEmail()).isEqualTo("ada@x.test");
    assertThat(after.getCompanyId()).isEqualTo(before.getCompanyId());
    assertThat(after.getTeam()).isEqualTo(before.getTeam());
    assertThat(after.isActive()).isTrue(); // untouched: a reporting flag is not a soft delete
  }

  /**
   * A dry run over an ALREADY-POPULATED roster must not write. This is the case the obvious dry-run
   * test misses.
   *
   * <p>{@code importRoster} is {@code @Transactional} and {@code findBySiteIdAndPin} returns a MANAGED
   * entity, so setting fields on it and then declining to call {@code save()} undoes nothing —
   * Hibernate dirty-checks and flushes at commit. Against an empty table every row is new and never
   * managed, so the naive test passes while the real behaviour is that pressing "Preview changes"
   * rewrites the roster: renaming people, re-companying them, and applying the CSV's deleted flag.
   */
  @Test
  void dryRunOverAnExistingRosterWritesNothing() {
    roster.applyRosterImport(siteId, fixture());
    var before = people.findBySiteIdAndPin(siteId, "1").orElseThrow();
    assertThat(before.getName()).isEqualTo("Ada Placeholder");

    String mutated =
        header()
            + "\n"
            + row("001", "REWRITTEN", "rewritten@x.test", "Sphinix Technologies", "YES", "true");
    ImportReport report = roster.previewRosterImport(siteId, mutated);
    assertThat(report.committed()).isFalse();
    assertThat(report.updated()).isEqualTo(1);

    // Read straight from SQL so no first-level cache can mask a flush that already happened.
    var raw =
        jdbc.queryForMap(
            "SELECT \"name\",\"email\",\"team\",\"active\",\"excludedFromReports\","
                + "\"companyId\" FROM \"iclock_people\" WHERE \"siteId\"=? AND \"pin\"=?",
            siteId, "1");
    assertThat(raw.get("name")).isEqualTo("Ada Placeholder");
    assertThat(raw.get("email")).isEqualTo("ada@x.test");
    assertThat(raw.get("team")).isEqualTo("Team");
    assertThat(raw.get("active")).as("a dry run must never deactivate anyone").isEqualTo(true);
    assertThat(raw.get("excludedFromReports")).isEqualTo(false);
    assertThat(raw.get("companyId")).isEqualTo(before.getCompanyId());
  }

  // ------------------------------------------------------------------- fixture

  private static CompanyTally tally(ImportReport report, String companyName) {
    return report.byCompany().stream()
        .filter(t -> companyName.equals(t.companyName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no tally for " + companyName + ": " + report.byCompany()));
  }

  private static String header() {
    return "pin,pin_canonical,name,email,company,team,role,late_exempt_min,deleted,source,excluded_from_reports";
  }

  private static String row(
      String pin, String name, String email, String company, String deleted, String excluded) {
    return String.join(
        ",", pin, pin, name, email, company, "Team", "Role", "0", deleted, "test", excluded);
  }

  /** Eleven fabricated people covering every branch the real seed exercises. */
  private static String fixture() {
    return String.join(
        "\n",
        header(),
        // Same company, three spellings of the label — must collapse to one tally.
        row("001", "Ada Placeholder", "ada@x.test", "Screatives Software Services", "", ""),
        row("002", "Bo Placeholder", "bo@x.test", "Screatives", "", ""),
        row("003", "Cy Placeholder", "cy@x.test", "screatives", "", ""),
        // Alias path: the terminal's short label and the full IHRMS name.
        row("004", "Di Placeholder", "di@x.test", "Combino IT", "", ""),
        row("005", "Ed Placeholder", "ed@x.test", "Combino Information Technologies", "", ""),
        row("006", "Fi Placeholder", "fi@x.test", "Charm Info", "", ""),
        row("007", "Gu Placeholder", "gu@x.test", "Charm Info Systems", "", ""),
        // Excluded from reports, and separately, inactive.
        row("905", "Hy Placeholder", "hy@x.test", "Sphinix Technologies", "", "true"),
        row("906", "Iz Placeholder", "iz@x.test", "Spire Info Tech", "yes", ""),
        // No company at all — listed individually, never guessed at.
        row("901", "Jo Placeholder", "jo@x.test", "", "", ""),
        row("902", "Ka Placeholder", "ka@x.test", "", "", ""));
  }
}
