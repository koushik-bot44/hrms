package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Changing a terminal's role or building is FORWARD-ONLY, and must not disturb {@code claimedAt}.
 *
 * <p>That timestamp is not decoration: it is the attribution window. It bounds re-resolution, the
 * inbox's live/archive split and the operator sweep. Fixing a mislabelled terminal by re-claiming it
 * would shove that boundary to now, and punches already attributed would fall out of the live window —
 * the console would start describing tonight's traffic as archive, with nothing to indicate why.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockDeviceEditTest {

  @Autowired IclockAdminService admin;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockDeviceRepository devices;
  @Autowired JdbcTemplate jdbc;

  private static final Instant CLAIMED_AT = Instant.parse("2026-08-20T03:30:00Z");

  private String siteA;
  private String siteB;
  private String deviceId;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"iclock_site_policies\",\"iclock_punch_members\",\"iclock_punches\","
            + "\"iclock_raw_punches\",\"iclock_people\",\"iclock_employee_pins\",\"iclock_devices\","
            + "\"iclock_site_companies\",\"iclock_sites\",\"audit_logs\" RESTART IDENTITY CASCADE");

    IclockSite a = new IclockSite();
    a.setName("Building A");
    siteA = sites.save(a).getId();
    IclockSite b = new IclockSite();
    b.setName("Building B");
    siteB = sites.save(b).getId();

    IclockDevice d = new IclockDevice();
    d.setSerialNumber("EDITTEST1");
    d.setStatus("CLAIMED");
    d.setSiteId(siteA);
    d.setArea("GATE");
    d.setDirection("IN");
    d.setClaimedAt(CLAIMED_AT);
    deviceId = devices.save(d).getId();
  }

  @Test
  void changingRoleLeavesTheAttributionWindowWhereItWas() {
    admin.changeDeviceRole(deviceId, "CAFETERIA", "OUT");

    IclockDevice after = devices.findById(deviceId).orElseThrow();
    assertThat(after.getArea()).isEqualTo("CAFETERIA");
    assertThat(after.getDirection()).isEqualTo("OUT");
    assertThat(after.getClaimedAt())
        .as("claimedAt is the attribution window — a role fix must not move it")
        .isEqualTo(CLAIMED_AT);
    assertThat(after.getSiteId()).isEqualTo(siteA);
    assertThat(after.getStatus()).isEqualTo("CLAIMED");
  }

  @Test
  void movingBuildingLeavesTheAttributionWindowWhereItWas() {
    admin.moveDeviceToSite(deviceId, siteB);

    IclockDevice after = devices.findById(deviceId).orElseThrow();
    assertThat(after.getSiteId()).isEqualTo(siteB);
    assertThat(after.getClaimedAt()).isEqualTo(CLAIMED_AT);
    // The role is untouched by a move — they are separate corrections.
    assertThat(after.getArea()).isEqualTo("GATE");
    assertThat(after.getDirection()).isEqualTo("IN");
  }

  @Test
  void reClaimingWOULDMoveTheWindow_whichIsWhyTheseAreSeparateOperations() {
    // Not a bug in claimDevice — adopting a terminal SHOULD start its window. This test exists to pin
    // the difference, so nobody "simplifies" the role editors into a re-claim later.
    //
    // The serials here deliberately avoid the ^ZZTEST prefix: that is reserved for synthetic probes
    // and claimDevice refuses it outright, which is the guard doing its job — a diagnostic probe once
    // polluted a real device row, and the prefix exists so it cannot happen again.
    admin.claimDevice(
        deviceId,
        new com.ihrms.iclock.dto.IclockAdminDtos.ClaimDeviceRequest(siteA, "GATE", "OUT", null));

    assertThat(devices.findById(deviceId).orElseThrow().getClaimedAt())
        .as("claiming re-stamps the window; the role/building editors deliberately do not")
        .isAfter(CLAIMED_AT);
  }

  @Test
  void anUnclaimedTerminalCannotBeGivenARoleOrMoved() {
    IclockDevice fresh = new IclockDevice();
    fresh.setSerialNumber("EDITTEST2");
    fresh.setStatus("UNCLAIMED");
    String id = devices.save(fresh).getId();

    // A role without a building is meaningless — the punch has nowhere to be attributed.
    assertThatThrownBy(() -> admin.changeDeviceRole(id, "GATE", "IN"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Claim this terminal");
    assertThatThrownBy(() -> admin.moveDeviceToSite(id, siteB))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Claim this terminal");
  }

  @Test
  void movingToAnUnknownBuildingIsRefused() {
    assertThatThrownBy(() -> admin.moveDeviceToSite(deviceId, "no-such-building"))
        .isInstanceOf(ResponseStatusException.class);
    // And the terminal is left exactly as it was.
    assertThat(devices.findById(deviceId).orElseThrow().getSiteId()).isEqualTo(siteA);
  }
}
