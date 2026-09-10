package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.dto.IclockRosterDtos.UpsertPersonRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Editing a person: company from a closed list, team from an open one, and the two things the edit
 * path used to get wrong.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockPersonEditTest {

  @Autowired IclockRosterService roster;
  @Autowired IclockPersonRepository people;
  @Autowired IclockSiteRepository sites;
  @Autowired CompanyRepository companies;

  private String siteId;
  private String tag;
  private Company screatives;
  private Company combino;

  private static UpsertPersonRequest req(
      String pin, String name, String email, String companyId, String team) {
    return new UpsertPersonRequest(
        pin, name, email, companyId, null, team, null, null, null, null, null);
  }

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    IclockSite s = new IclockSite();
    s.setName("Edit Site " + tag);
    s.setTimezone(ShiftConfig.ZONE.getId());
    siteId = sites.save(s).getId();

    screatives = company("Screatives " + tag, "SCR" + tag.toUpperCase(), "ACTIVE", null);
    combino = company("Combino " + tag, "CMB" + tag.toUpperCase(), "ACTIVE", null);
  }

  private Company company(String name, String code, String status, Instant deletedAt) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    c.setStatus(status);
    c.setDeletedAt(deletedAt);
    return companies.save(c);
  }

  private IclockPerson person(String pin, String name, String email) {
    IclockPerson p = new IclockPerson();
    p.setSiteId(siteId);
    p.setPin(pin);
    p.setName(name);
    p.setEmail(email);
    return people.save(p);
  }

  // ------------------------------------------------------- the company picker

  @Test
  void theCompanyPickerOffersOnlyLiveCompanies() {
    Company gone = company("Deleted " + tag, "DEL" + tag.toUpperCase(), "ACTIVE", Instant.now());
    Company suspended = company("Suspended " + tag, "SUS" + tag.toUpperCase(), "SUSPENDED", null);

    var options = roster.activeCompanies();
    var ids = options.stream().map(IclockRosterService.CompanyOption::id).toList();

    assertThat(ids).contains(screatives.getId(), combino.getId());
    // Assigning somebody to a company the rest of IHRMS considers gone would put live attendance
    // under a dead entity — and nothing downstream would notice.
    assertThat(ids).doesNotContain(gone.getId()).doesNotContain(suspended.getId());
  }

  @Test
  void changingCompanyRewritesTheLabelSoTheTwoAgree() {
    // THE BUG THIS PINS. companyLabel is the import's verbatim text and is what every grouped surface
    // falls back to. Leaving a stale label on somebody just moved would show them under their OLD
    // company on the board while reports used the new one.
    IclockPerson p = person("5001", "Mover " + tag, null);
    p.setCompanyId(combino.getId());
    p.setCompanyLabel("Combino IT");
    people.save(p);

    var view = roster.editPerson(p.getId(), req(null, null, null, screatives.getId(), null));

    assertThat(view.companyId()).isEqualTo(screatives.getId());
    assertThat(view.companyName()).isEqualTo(screatives.getName());
    assertThat(people.findById(p.getId()).orElseThrow().getCompanyLabel())
        .as("the stale label must not survive the move")
        .isEqualTo(screatives.getName());
  }

  @Test
  void anExplicitLabelStillWins_becauseThatIsTheImportsOwnPath() {
    IclockPerson p = person("5002", "Labelled " + tag, null);
    var view = roster.editPerson(
        p.getId(),
        new UpsertPersonRequest(
            null, null, null, screatives.getId(), "Screatives (as printed)",
            null, null, null, null, null, null));

    assertThat(view.companyId()).isEqualTo(screatives.getId());
    assertThat(people.findById(p.getId()).orElseThrow().getCompanyLabel())
        .isEqualTo("Screatives (as printed)");
  }

  // ---------------------------------------------------------- the team picker

  @Test
  void theTeamPickerOffersWhatIsAlreadyInUseAtThisBuilding() {
    person("6001", "A " + tag, null).setTeam("Kiran Team");
    IclockPerson a = people.findBySiteIdAndPin(siteId, "6001").orElseThrow();
    a.setTeam("Kiran Team");
    people.save(a);

    IclockPerson b = person("6002", "B " + tag, null);
    b.setTeam("Sam Team");
    people.save(b);

    IclockPerson c = person("6003", "C " + tag, null);
    c.setTeam("Kiran Team"); // a duplicate must not produce a duplicate option
    people.save(c);

    assertThat(roster.teamsAt(siteId)).containsExactly("Kiran Team", "Sam Team");
  }

  @Test
  void aBrandNewTeamIsAcceptedBecauseTeamsAreLabelsNotEntities() {
    IclockPerson p = person("6004", "New Team " + tag, null);
    var view = roster.editPerson(p.getId(), req(null, null, null, null, "Night Ops " + tag));

    assertThat(view.team()).isEqualTo("Night Ops " + tag);
    assertThat(roster.teamsAt(siteId)).contains("Night Ops " + tag);
  }

  // ------------------------------------------------------------------ e-mail

  @Test
  void anEditThatCreatesADuplicateEmailComesBackFlagged() {
    // THE SECOND BUG. editPerson returned Set.of() for duplicates, so an edit that introduced a
    // collision reported no flag and the operator only found out on the next full roster load.
    person("7001", "First " + tag, "shared-" + tag + "@x.test");
    IclockPerson second = person("7002", "Second " + tag, "unique-" + tag + "@x.test");

    var view = roster.editPerson(
        second.getId(), req(null, null, "shared-" + tag + "@x.test", null, null));

    assertThat(view.duplicateEmail())
        .as("flagged on the response, not two screens later")
        .isTrue();
    assertThat(people.findById(second.getId()).orElseThrow().getEmail())
        .as("flagged, never blocked — the real seed data shares an address between two people")
        .isEqualTo("shared-" + tag + "@x.test");
  }

  @Test
  void aUniqueEmailIsNotFlagged() {
    IclockPerson p = person("7003", "Solo " + tag, null);
    var view = roster.editPerson(p.getId(), req(null, null, "solo-" + tag + "@x.test", null, null));
    assertThat(view.duplicateEmail()).isFalse();
  }

  @Test
  void nameAndEmailAndCompanyAndTeamMoveTogetherInOneEdit() {
    IclockPerson p = person("8001", "Before " + tag, null);

    var view = roster.editPerson(
        p.getId(),
        req(null, "After " + tag, "after-" + tag + "@x.test", combino.getId(), "Accounts " + tag));

    assertThat(view.name()).isEqualTo("After " + tag);
    assertThat(view.email()).isEqualTo("after-" + tag + "@x.test");
    assertThat(view.companyName()).isEqualTo(combino.getName());
    assertThat(view.team()).isEqualTo("Accounts " + tag);
    assertThat(view.pin()).as("the pin is untouched by a details edit").isEqualTo("8001");
  }

  // ------------------------------------------------------------------ adding a person

  @Test
  void addingAPersonWithAPinTHATALREADYEXISTSIsRefusedNotSilentlyMerged() {
    // The add dialog and the roster importer want opposite things from a pin already present. The
    // importer is correcting a row it expects to find; somebody typing into a dialog believes they
    // are creating a new person. Merging the second case lets a mistyped pin overwrite a colleague's
    // name, company and shift, and the only symptom is that person's report going wrong weeks later.
    roster.createPerson(siteId, req("40001", "First Arrival " + tag, null, null, null));

    assertThatThrownBy(() ->
        roster.createPerson(siteId, req("40001", "Typo Twin " + tag, null, null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("already on this building's roster");

    assertThat(people.findBySiteIdAndPin(siteId, "40001"))
        .get()
        .extracting(IclockPerson::getName)
        .asString()
        .startsWith("First Arrival");
  }

  @Test
  void aDeactivatedPersonBlocksTheirPinTooAndSaysSo() {
    // Otherwise "add" quietly creates a second row for somebody who is already there, and the
    // building ends up with two histories for one pin.
    var created = roster.createPerson(siteId, req("40002", "Gone Away " + tag, null, null, null));
    IclockPerson p = people.findById(created.id()).orElseThrow();
    p.setActive(false);
    people.save(p);

    assertThatThrownBy(() ->
        roster.createPerson(siteId, req("40002", "Second Row " + tag, null, null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("reactivate");
  }

  @Test
  void anUnusablePinIsRefusedBeforeAnythingIsWritten() {
    assertThatThrownBy(() -> roster.createPerson(siteId, req("0000", "Zeroes " + tag, null, null, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> roster.createPerson(siteId, req("abc", "Letters " + tag, null, null, null)))
        .isInstanceOf(ResponseStatusException.class);
  }
}
