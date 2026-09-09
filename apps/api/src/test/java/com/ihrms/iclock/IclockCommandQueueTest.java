package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockDeviceCommand;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceCommandRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * The queue's safety properties, against a real database.
 *
 * <p>This is the first path that writes to hardware, so what is asserted here is mostly what the
 * system REFUSES to do: serve while switched off, delete an active person's enrolment, accept an
 * unbounded queue, or retry forever.
 */
@SpringBootTest(properties = "app.iclock.commands-enabled=true")
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockCommandQueueTest {

  @Autowired IclockCommandService commands;
  @Autowired IclockDeviceCommandRepository repo;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockPersonRepository people;
  @Autowired IclockSiteRepository sites;

  private String tag;
  private IclockSite site;
  private IclockDevice gateIn;
  private IclockDevice gateOut;

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    site = new IclockSite();
    site.setName("Cmd Site " + tag);
    site.setTimezone(ShiftConfig.ZONE.getId());
    site = sites.save(site);
    gateIn = device("CIN" + tag.toUpperCase(), "IN");
    gateOut = device("COUT" + tag.toUpperCase(), "OUT");
  }

  private IclockDevice device(String serial, String dir) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setSiteId(site.getId());
    d.setArea("GATE");
    d.setDirection(dir);
    d.setStatus("CLAIMED");
    d.setClaimedAt(Instant.parse("2026-01-01T00:00:00Z"));
    return devices.save(d);
  }

  private IclockPerson person(String pin, String name, boolean active) {
    IclockPerson p = new IclockPerson();
    p.setSiteId(site.getId());
    p.setPin(pin);
    p.setName(name);
    p.setActive(active);
    return people.save(p);
  }

  // ------------------------------------------------------------------ queueing

  @Test
  void aNamePushQueuesOnePerClaimedTerminalAtThatBuilding() {
    // One per device, because a pin is enrolled on every terminal in the building and the name shows
    // on whichever one the person walks up to. Updating one would leave the fleet disagreeing.
    IclockPerson p = person("5101", "Anil Kumar " + tag, true);

    var queued = commands.queueNameUpdate(p.getId(), "usr_test");

    assertThat(queued).hasSize(2);
    assertThat(queued).extracting(IclockDeviceCommand::getDeviceId)
        .containsExactlyInAnyOrder(gateIn.getId(), gateOut.getId());
    assertThat(queued).allSatisfy(c -> {
      assertThat(c.getStatus()).isEqualTo("PENDING");
      assertThat(c.getCreatedBy()).isEqualTo("usr_test");
      assertThat(c.getPayload()).startsWith("DATA UPDATE USERINFO PIN=5101\tName=Anil Kumar");
    });
  }

  @Test
  void anUnnamedPersonCannotBePushed() {
    // Pushing an empty name blanks the screen — strictly worse than the slug it would replace.
    IclockPerson p = person("5102", null, true);
    assertThatThrownBy(() -> commands.queueNameUpdate(p.getId(), "usr_test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("no name yet");
  }

  // ------------------------------------------------------------- the kill switch

  @Test
  void withTheSwitchONaQueuedCommandIsServedExactlyOnce() {
    IclockPerson p = person("5103", "Served Once " + tag, true);
    commands.queueNameUpdate(p.getId(), "usr_test");

    var first = commands.nextFor(gateIn.getSerialNumber());
    assertThat(first).isPresent();
    assertThat(first.get()).startsWith("C:").contains("DATA UPDATE USERINFO PIN=5103");

    // SENT, not still PENDING. Leaving it pending would re-serve the same command a second later,
    // which for a device that acts on it but acks slowly means executing it repeatedly.
    var second = commands.nextFor(gateIn.getSerialNumber());
    assertThat(second).as("not handed out twice in a row").isEmpty();
  }

  @Test
  void anUnclaimedTerminalIsNeverServed() {
    IclockDevice stray = new IclockDevice();
    stray.setSerialNumber("CSTRAY" + tag.toUpperCase());
    stray.setStatus("UNCLAIMED");
    devices.save(stray);

    assertThat(commands.nextFor(stray.getSerialNumber())).isEmpty();
    assertThat(commands.nextFor("NOSUCHSERIAL" + tag)).isEmpty();
    assertThat(commands.nextFor(null)).isEmpty();
  }

  // ------------------------------------------------------------------- the ack

  @Test
  void aPositiveReturnAcksAndANegativeOneFails() {
    IclockPerson p = person("5104", "Acked " + tag, true);
    String id = commands.queueNameUpdate(p.getId(), "usr_test").get(0).getId();
    commands.nextFor(gateIn.getSerialNumber());

    commands.recordAck(id, "0", "ID=" + id + "&Return=0&CMD=DATA");

    var done = repo.findById(id).orElseThrow();
    assertThat(done.getStatus()).isEqualTo("ACKED");
    assertThat(done.getAckReturn()).isEqualTo("0");
    assertThat(done.getAckRaw()).contains("Return=0");
    assertThat(done.getCompletedAt()).isNotNull();
  }

  @Test
  void aNegativeReturnIsRecordedAsFailedWithTheReason() {
    IclockPerson p = person("5105", "Failed " + tag, true);
    String id = commands.queueNameUpdate(p.getId(), "usr_test").get(0).getId();
    commands.nextFor(gateIn.getSerialNumber());

    commands.recordAck(id, "-14", "ID=" + id + "&Return=-14");

    var done = repo.findById(id).orElseThrow();
    assertThat(done.getStatus()).isEqualTo("FAILED");
    assertThat(done.getFailureReason()).contains("-14");
  }

  @Test
  void anAckForAnUnknownCommandIsShruggedOffNotThrown() {
    // The ack format is unproven on this fleet. An unrecognised reply must be survivable: the raw
    // capture filter has the request either way, which is how the real shape gets discovered.
    commands.recordAck("cmd_does_not_exist", "0", "whatever");
    commands.recordAck(null, "0", "no id at all");
    commands.recordAck("", null, "");
  }

  // --------------------------------------------------------------- the bounds

  @Test
  void aCommandServedTooOftenWithoutAnAckGivesUpAndIsSurfaced() {
    // Serving forever would mean an attempt every poll, per device, indefinitely.
    IclockPerson p = person("5106", "Gives Up " + tag, true);
    String id = commands.queueNameUpdate(p.getId(), "usr_test").get(0).getId();

    // Default cap is 3. Force it back to PENDING between serves, as an un-acked command effectively
    // is when a device takes it and says nothing.
    for (int i = 0; i < 4; i++) {
      var c = repo.findById(id).orElseThrow();
      if ("SENT".equals(c.getStatus())) {
        c.setStatus("PENDING");
        repo.saveAndFlush(c);
      }
      commands.nextFor(gateIn.getSerialNumber());
    }

    var dead = repo.findById(id).orElseThrow();
    assertThat(dead.getStatus()).isEqualTo("FAILED");
    assertThat(dead.getFailureReason()).contains("no acknowledgement");
    assertThat(commands.nextFor(gateIn.getSerialNumber()))
        .as("a failed command is not served again")
        .isEmpty();
  }

  // ------------------------------------------------------- the delete guard

  @Test
  void anActivePersonsEnrolmentCannotBeDeleted() {
    // Deleting it removes their fingerprint from the terminal and leaves them unable to get in until
    // they physically re-register. No console button should be able to cause that by a mis-click.
    person("5107", "Still Here " + tag, true);

    assertThatThrownBy(() -> commands.queueUserDelete(gateIn.getId(), "5107", "usr_test"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("still active");
  }

  @Test
  void aDeactivatedPersonOrAnUnknownPinMayBeDeleted() {
    person("5108", "Gone " + tag, false);

    var forLeaver = commands.queueUserDelete(gateIn.getId(), "5108", "usr_test");
    assertThat(forLeaver.getPayload()).isEqualTo("DATA DELETE USERINFO PIN=5108");

    var forGhost = commands.queueUserDelete(gateIn.getId(), "5109", "usr_test");
    assertThat(forGhost.getPayload()).isEqualTo("DATA DELETE USERINFO PIN=5109");
    assertThat(forGhost.getPersonId()).as("nobody behind it").isNull();
  }

  // ------------------------------------------------------------- bulk preview

  @Test
  void theBulkPreviewQueuesNothing() {
    person("5110", "Bulk One " + tag, true);
    person("5111", null, true);

    var preview = commands.previewNameSync(site.getId());

    assertThat(preview.committed()).isFalse();
    assertThat(preview.people()).isEqualTo(1);
    assertThat(preview.skipped()).as("the unnamed one is skipped, not blanked").isEqualTo(1);
    assertThat(preview.devices()).isEqualTo(2);
    assertThat(preview.commandsQueued()).as("1 person x 2 terminals").isEqualTo(2);
    assertThat(repo.findByDeviceIdOrderByCreatedAtDesc(
        gateIn.getId(), org.springframework.data.domain.PageRequest.of(0, 10)))
        .as("a preview writes nothing")
        .isEmpty();
  }

  @Test
  void theBulkCommitQueuesOnePerPersonPerDevice() {
    person("5112", "Bulk A " + tag, true);
    person("5113", "Bulk B " + tag, true);

    var report = commands.syncNames(site.getId(), "usr_test");

    assertThat(report.committed()).isTrue();
    assertThat(report.people()).isEqualTo(2);
    assertThat(report.commandsQueued()).isEqualTo(4);
    assertThat(repo.countByDeviceIdAndStatus(gateIn.getId(), "PENDING")).isEqualTo(2);
    assertThat(repo.countByDeviceIdAndStatus(gateOut.getId(), "PENDING")).isEqualTo(2);
  }
}
