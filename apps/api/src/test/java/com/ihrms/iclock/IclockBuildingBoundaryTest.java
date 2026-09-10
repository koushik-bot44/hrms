package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * <b>Buildings do not share registers.</b>
 *
 * <p>Written after an incident, and the incident is the reason it is phrased the way it is. Every
 * caller in the command service was already building-scoped, and the fleet-wide name sync obeyed
 * those scopes exactly — and 2A's terminals still ended up displaying people who work at Building 9.
 * The queries were right; the roster they trusted was wrong, because rows created in the original
 * import left thirteen people active at a building they had left.
 *
 * <p>So the rule under test is not "each caller filters by site". It is that a command carrying a
 * person to a terminal in a building where they hold no ACTIVE roster row cannot be constructed at
 * all, whatever the caller believed. A scope is a property of one code path; this is a property of
 * the system.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockBuildingBoundaryTest {

  @Autowired private IclockCommandService commands;
  @Autowired private IclockSiteRepository sites;
  @Autowired private IclockDeviceRepository devices;
  @Autowired private IclockPersonRepository people;

  private IclockSite here;
  private IclockSite there;
  private IclockDevice terminalHere;
  private IclockPerson worksHere;
  private IclockPerson worksThere;

  @BeforeEach
  void setUp() {
    here = site("Boundary A");
    there = site("Boundary B");
    terminalHere = terminal(here, "BND" + System.nanoTime());
    worksHere = person(here, "990001", "Belongs Here", true);
    worksThere = person(there, "990002", "Belongs There", true);
  }

  // ------------------------------------------------------------------ the rule

  @Test
  void aNamePushToSomebodysOwnBuildingIsAllowed() {
    assertDoesNotThrow(() -> commands.queueNameUpdate(worksHere.getId(), "test"));
  }

  @Test
  void aNamePushCANNOTReachATerminalInAnotherBuilding() {
    // queueNameUpdate fans out over the person's OWN building, so the other building's terminal is
    // never a target — and with no terminal at all in their building, it refuses outright rather
    // than quietly reaching for the nearest one.
    assertThatThrownBy(() -> commands.queueNameUpdate(worksThere.getId(), "test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("No claimed terminal");

    assertThat(commands.logFor(terminalHere.getId(), 50)).isEmpty();
  }

  @Test
  void anEnrolmentIsREFUSEDOnATerminalInAnotherBuilding() {
    // The explicit cross-building attempt: an operator picks the wrong terminal from a dropdown.
    assertThatThrownBy(() -> commands.queueEnrolment(
        worksThere.getId(), terminalHere.getId(), IclockCommandDialect.TYPE_FINGERPRINT, 0, "test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("different building");
  }

  @Test
  void aDEACTIVATEDRosterRowIsNotAnActiveOneAndDoesNotOpenTheDoor() {
    // THE INCIDENT, EXACTLY. The people whose names reached the wrong screens all held roster rows
    // at a building they had left. Deactivating is what the console does when somebody moves, so
    // "has a row here" must never be enough — it has to be an ACTIVE row.
    IclockPerson moved = person(here, "990003", "Moved Away", false);

    assertThatThrownBy(() -> commands.queueEnrolment(
        moved.getId(), terminalHere.getId(), IclockCommandDialect.TYPE_FINGERPRINT, 0, "test"))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void removalIsEXEMPTBecauseItIsTheRemedyNotTheDamage() {
    // Deleting somebody from a building they do not belong to is the CLEANUP for a boundary breach.
    // A rule that blocked the repair along with the damage would leave a polluted register with no
    // way to clear it from here.
    IclockPerson gone = person(here, "990004", "Gone", false);

    assertDoesNotThrow(() ->
        commands.queueUserDelete(terminalHere.getId(), gone.getPin(), "test"));
    assertThat(commands.logFor(terminalHere.getId(), 50))
        .extracting(c -> c.getKind())
        .containsExactly("DELETE_USER");
  }

  @Test
  void thereIsNoBulkPathLEFTToCrossTheLineWith() {
    // The building-wide name sync is gone. This asserts its absence rather than its behaviour: it
    // ran once, correctly scoped, and still did the damage, because a bulk write is only as right as
    // the roster beneath it and nobody is looking when it fires.
    assertThat(IclockCommandService.class.getMethods())
        .extracting(java.lang.reflect.Method::getName)
        .doesNotContain("syncNames", "previewNameSync");
  }

  // ------------------------------------------------------------------ fixtures

  private IclockSite site(String name) {
    IclockSite s = new IclockSite();
    s.setName(name + " " + System.nanoTime());
    s.setTimezone("Asia/Kolkata");
    return sites.save(s);
  }

  private IclockDevice terminal(IclockSite site, String serial) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setName("Boundary terminal");
    // A CLAIMED row must be claim-COMPLETE: site, area, direction and claimedAt together, per the
    // V43 check constraint. Half a claim is not a lighter version of one.
    d.setStatus("CLAIMED");
    d.setSiteId(site.getId());
    d.setArea("GATE");
    d.setDirection("IN");
    d.setClaimedAt(java.time.Instant.now());
    return devices.save(d);
  }

  private IclockPerson person(IclockSite site, String pin, String name, boolean active) {
    IclockPerson p = new IclockPerson();
    p.setSiteId(site.getId());
    p.setPin(pin + System.nanoTime() % 1000);
    p.setName(name);
    p.setActive(active);
    return people.save(p);
  }

  /** Kept so the unused-field warning does not hide a fixture that later matters. */
  @SuppressWarnings("unused")
  private List<String> unused() {
    return List.of(there.getId());
  }
}
