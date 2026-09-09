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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * THE KILL SWITCH, in its OFF position — which is the position that matters.
 *
 * <p>Deliberately its own class with its own Spring context. {@link IclockCommandQueueTest} turns the
 * switch on to exercise the queue, and a test that only ever runs with a feature enabled proves
 * nothing about the guard that is supposed to hold it shut. The default here is the shipped default:
 * no property is set at all, so this asserts what production gets when nobody has touched anything.
 *
 * <p>The distinction being pinned: QUEUEING is always allowed, SERVING is not. An operator can line
 * work up while the channel is closed, and none of it reaches a terminal until somebody opens it.
 * That is what makes "deploy dark" true rather than aspirational.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockCommandKillSwitchTest {

  @Autowired IclockCommandService commands;
  @Autowired IclockDeviceCommandRepository repo;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockPersonRepository people;
  @Autowired IclockSiteRepository sites;

  @Test
  void theChannelIsOffUnlessSomebodyTurnsItOn() {
    assertThat(commands.enabled())
        .as("the shipped default, with no property set")
        .isFalse();
  }

  @Test
  void withTheSwitchOFFnothingIsServedHoweverMuchIsQueued() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    IclockSite site = new IclockSite();
    site.setName("Dark Site " + tag);
    site.setTimezone(ShiftConfig.ZONE.getId());
    site = sites.save(site);

    IclockDevice gate = new IclockDevice();
    gate.setSerialNumber("DARK" + tag.toUpperCase());
    gate.setSiteId(site.getId());
    gate.setArea("GATE");
    gate.setDirection("IN");
    gate.setStatus("CLAIMED");
    gate.setClaimedAt(Instant.parse("2026-01-01T00:00:00Z"));
    gate = devices.save(gate);

    IclockPerson p = new IclockPerson();
    p.setSiteId(site.getId());
    p.setPin("6101");
    p.setName("Queued But Silent " + tag);
    p = people.save(p);

    // Queueing is allowed with the channel shut — that is the point of deploying dark.
    var queued = commands.queueNameUpdate(p.getId(), "usr_test");
    assertThat(queued).isNotEmpty();
    assertThat(repo.countByDeviceIdAndStatus(gate.getId(), "PENDING")).isPositive();

    // Serving is not.
    assertThat(commands.nextFor(gate.getSerialNumber()))
        .as("a closed channel serves nothing, whatever is waiting")
        .isEmpty();

    // And the command is untouched — not consumed, not counted as served, still waiting for the day
    // somebody opens the channel deliberately.
    var still = repo.findById(queued.get(0).getId()).orElseThrow();
    assertThat(still.getStatus()).isEqualTo("PENDING");
    assertThat(still.getServeCount()).isZero();
    assertThat(still.getSentAt()).isNull();
  }
}
