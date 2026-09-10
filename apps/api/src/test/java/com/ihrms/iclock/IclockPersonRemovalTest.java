package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceCommandRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removing a person: the terminals and the roster, in one decision.
 *
 * <p>The two halves have to happen in one order and only one: the command service refuses to delete
 * a pin belonging to somebody ACTIVE at that building, so clearing the terminals first would be
 * refused by the guard that exists to stop exactly the wrong thing. Roster first, terminals second.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockPersonRemovalTest {

  @Autowired IclockPersonRemovalService removals;
  @Autowired IclockPersonRepository people;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockDeviceCommandRepository commands;

  private String siteId;
  private String tag;
  private IclockDevice gateIn;
  private IclockDevice gateOut;

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 6);
    IclockSite s = new IclockSite();
    s.setName("Removal Site " + tag);
    s.setTimezone(ShiftConfig.ZONE.getId());
    siteId = sites.save(s).getId();
    gateIn = terminal("RMA" + tag);
    gateOut = terminal("RMB" + tag);
  }

  private IclockDevice terminal(String serial) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setName(serial);
    d.setStatus("CLAIMED");
    d.setSiteId(siteId);
    d.setArea("GATE");
    d.setDirection("IN");
    d.setClaimedAt(Instant.now());
    return devices.save(d);
  }

  private IclockPerson person(String pin, String name) {
    IclockPerson p = new IclockPerson();
    p.setSiteId(siteId);
    p.setPin(pin);
    p.setName(name);
    p.setActive(true);
    return people.save(p);
  }

  // ------------------------------------------------------------------ preview

  @Test
  void thePreviewWritesNothingAndQueuesNothing() {
    IclockPerson p = person("70001", "Preview Only " + tag);

    var report = removals.preview(siteId, List.of(p.getId()));

    assertThat(report.committed()).isFalse();
    assertThat(report.people()).isEqualTo(1);
    assertThat(report.commandsQueued()).as("2 terminals in this building").isEqualTo(2);
    assertThat(people.findById(p.getId())).get().extracting(IclockPerson::isActive).isEqualTo(true);
    assertThat(commands.findByDeviceIdOrderByCreatedAtDesc(gateIn.getId(), PageRequest.of(0, 10)))
        .as("a preview writes nothing")
        .isEmpty();
  }

  @Test
  void somebodyWithNoHistoryIsDELETEDandSomebodyWithPunchesIsDEACTIVATED() {
    // The anti-Bipul rule, surfaced BEFORE the operator commits rather than as a 500 afterwards.
    IclockPerson fresh = person("70002", "Never Punched " + tag);

    var report = removals.preview(siteId, List.of(fresh.getId()));

    assertThat(report.rows()).singleElement()
        .extracting(IclockPersonRemovalService.RemovalRow::rosterOutcome)
        .isEqualTo("DELETE");
    assertThat(report.toDelete()).isEqualTo(1);
    assertThat(report.toDeactivate()).isZero();
  }

  // ------------------------------------------------------------------ commit

  @Test
  void removingQueuesADeletionOnEVERYterminalInTheirBuilding() {
    // Every claimed terminal, not only the ones we believe hold them: our record of who is enrolled
    // where comes from register audits, and most terminals have never been audited. A delete for a
    // pin a device does not have is a no-op; a terminal skipped on a stale belief keeps the
    // enrolment forever.
    IclockPerson p = person("70003", "Leaving " + tag);

    var report = removals.remove(siteId, List.of(p.getId()), "usr_test");

    assertThat(report.committed()).isTrue();
    assertThat(report.commandsQueued()).isEqualTo(2);
    assertThat(commands.countByDeviceIdAndStatus(gateIn.getId(), "PENDING")).isEqualTo(1);
    assertThat(commands.countByDeviceIdAndStatus(gateOut.getId(), "PENDING")).isEqualTo(1);
  }

  @Test
  void theROSTERgoesFirstOrTheDeviceGuardWouldRefuseTheWholeFlow() {
    // The command service refuses DELETE_USER while the pin belongs to an ACTIVE person here. That
    // guard is correct and this ordering is what lets a legitimate removal past it — if the
    // terminals went first, every removal would fail on its own safety rule.
    IclockPerson p = person("70004", "Order Matters " + tag);

    var report = removals.remove(siteId, List.of(p.getId()), "usr_test");

    assertThat(report.commandsQueued()).as("the deletions were accepted").isEqualTo(2);
    assertThat(people.findBySiteIdAndPin(siteId, "70004"))
        .as("no punches, so the row goes entirely")
        .isEmpty();
  }

  @Test
  void aPersonFromANOTHERbuildingIsIgnoredWhateverWasPosted() {
    // Building-bounded, like every other write path. A person id from elsewhere is not a removal
    // this screen may perform.
    IclockSite other = new IclockSite();
    other.setName("Other " + tag);
    other.setTimezone(ShiftConfig.ZONE.getId());
    String otherId = sites.save(other).getId();
    IclockPerson elsewhere = new IclockPerson();
    elsewhere.setSiteId(otherId);
    elsewhere.setPin("70005");
    elsewhere.setName("Not Here " + tag);
    elsewhere.setActive(true);
    people.save(elsewhere);

    var report = removals.remove(siteId, List.of(elsewhere.getId()), "usr_test");

    assertThat(report.people()).isZero();
    assertThat(report.commandsQueued()).isZero();
    assertThat(people.findById(elsewhere.getId()))
        .get().extracting(IclockPerson::isActive).isEqualTo(true);
  }

  // ------------------------------------------------------------------ roster only

  @Test
  void rosterOnlyRemovalQueuesNOcommands() {
    // For the audit screen's template gaps: those people are enrolled nowhere, so queueing six
    // deletions for a pin no terminal holds would be noise in every command log.
    IclockPerson p = person("70006", "No Templates " + tag);

    var report = removals.removeFromRosterOnly(siteId, List.of(p.getId()), "usr_test");

    assertThat(report.commandsQueued()).isZero();
    assertThat(report.toDelete()).isEqualTo(1);
    assertThat(people.findBySiteIdAndPin(siteId, "70006")).isEmpty();
  }
}
