package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-person shift assignment, end to end against a real database.
 *
 * <p>The pure derivation is pinned in {@link IclockShiftProfileTest}. What this file proves is that the
 * derivation actually reaches the punch: that promotion asks the PERSON which shift they work, and that
 * assigning somebody to the day shift changes how their punches are filed rather than only what a
 * settings screen displays.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockShiftAssignmentTest {

  @Autowired private IclockRosterService roster;
  @Autowired private IclockPromotionService promotion;
  @Autowired private IclockPersonRepository people;
  @Autowired private IclockPunchRepository punches;
  @Autowired private IclockSiteRepository sites;
  @Autowired private IclockDeviceRepository devices;
  @Autowired private IclockRawPunchRepository rawPunches;

  private IclockSite site;
  private IclockDevice gateIn;
  private IclockDevice gateOut;
  private String tag;

  private static final String DAY_PIN = "9001";
  private static final String NIGHT_PIN = "9002";

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    site = new IclockSite();
    site.setName("Shift Site " + tag);
    site.setTimezone(ShiftConfig.ZONE.getId());
    site = sites.save(site);

    gateIn = claimedDevice("SIN" + tag.toUpperCase(), "GATE", "IN");
    gateOut = claimedDevice("SOUT" + tag.toUpperCase(), "GATE", "OUT");
  }

  private IclockDevice claimedDevice(String serial, String area, String direction) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setSiteId(site.getId());
    d.setArea(area);
    d.setDirection(direction);
    d.setClaimedAt(Instant.parse("2026-01-01T00:00:00Z"));
    d.setStatus("CLAIMED");
    return devices.save(d);
  }

  private IclockPerson person(String pin, String name) {
    IclockPerson p = new IclockPerson();
    p.setSiteId(site.getId());
    p.setPin(pin);
    p.setName(name);
    return people.save(p);
  }

  private void punch(IclockDevice device, String pin, String istLocalTime) {
    IclockRawPunch raw = new IclockRawPunch();
    raw.setDeviceId(device.getId());
    raw.setSerialNumber(device.getSerialNumber());
    raw.setDevicePin(pin);
    raw.setPunchedAtRaw(istLocalTime);
    raw.setStatusCode("255");
    raw.setVerifyMode("15");
    raw.setRawLine(pin + "\t" + istLocalTime + "\t255\t15\t0");
    raw.setLineNumber(1);
    raw.setDedupeKey(IclockDedupe.key(device.getSerialNumber(), raw.getRawLine()));
    promotion.promote(rawPunches.save(raw).getId());
  }

  private List<LocalDate> shiftDatesOf(String personId) {
    return punches.findAll().stream()
        .filter(p -> personId.equals(p.getPersonId()))
        .sorted(java.util.Comparator.comparing(IclockPunch::getEffectiveAt))
        .map(IclockPunch::getShiftDate)
        .toList();
  }

  // ------------------------------------------------------------- the safe default

  @Test
  void aPersonSeededWithoutAnAssignmentIsOnTheNightShift() {
    // The whole migration rests on this: NIGHT is current behaviour, so an unassigned person must
    // behave exactly as they did before per-person shifts existed.
    assertThat(person(DAY_PIN, "Unassigned " + tag).getShiftProfile()).isEqualTo("NIGHT");
  }

  // ------------------------------------------------------- THE POINT OF THE FEATURE

  @Test
  void aDayShiftPersonsArrivalAndDepartureFileOnOneShiftDay() {
    IclockPerson p = person(DAY_PIN, "Day Worker " + tag);
    roster.assignShift(site.getId(), List.of(p.getId()), "DAY");

    punch(gateIn, DAY_PIN, "2026-08-27 09:00:00");
    punch(gateOut, DAY_PIN, "2026-08-27 18:00:00");

    assertThat(shiftDatesOf(p.getId()))
        .as("a day shift must not straddle two shift days")
        .containsExactly(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-27"));
  }

  @Test
  void theSamePunchesUnderTheNightProfileStillSplit_soTheAssignmentIsWhatDidIt() {
    // THE VACUITY NEGATIVE, at the integration level. If an unassigned person's 09:00/18:00 pair also
    // landed on one day, the test above would be proving something about the clock rather than about
    // the assignment.
    IclockPerson p = person(NIGHT_PIN, "Night Worker " + tag);

    punch(gateIn, NIGHT_PIN, "2026-08-27 09:00:00");
    punch(gateOut, NIGHT_PIN, "2026-08-27 18:00:00");

    assertThat(shiftDatesOf(p.getId()))
        .as("under the night cut the same two punches belong to different shift days")
        .containsExactly(LocalDate.parse("2026-08-26"), LocalDate.parse("2026-08-27"));
  }

  @Test
  void aDayShiftPersonsPostMidnightOvertimeStaysWithTheDayItStarted() {
    IclockPerson p = person(DAY_PIN, "Late Finisher " + tag);
    roster.assignShift(site.getId(), List.of(p.getId()), "DAY");

    punch(gateIn, DAY_PIN, "2026-08-27 09:00:00");
    punch(gateOut, DAY_PIN, "2026-08-28 00:30:00");

    assertThat(shiftDatesOf(p.getId()))
        .containsExactly(LocalDate.parse("2026-08-27"), LocalDate.parse("2026-08-27"));
  }

  // ---------------------------------------------------------------- the bulk action

  @Test
  void assignmentReportsWhatMovedSeparatelyFromWhatWasAlreadyRight() {
    IclockPerson a = person(DAY_PIN, "A " + tag);
    IclockPerson b = person(NIGHT_PIN, "B " + tag);
    roster.assignShift(site.getId(), List.of(a.getId()), "DAY");

    var report = roster.assignShift(site.getId(), List.of(a.getId(), b.getId()), "DAY");

    assertThat(report.changed()).as("only B actually moved").isEqualTo(1);
    assertThat(report.alreadyOnIt()).as("A was already on DAY").isEqualTo(1);
    assertThat(report.rows()).hasSize(2);
    assertThat(people.findById(b.getId()).orElseThrow().getShiftProfile()).isEqualTo("DAY");
  }

  @Test
  void anUnknownShiftNameIsRefusedRatherThanSilentlyTreatedAsNight() {
    IclockPerson p = person(DAY_PIN, "Whoever " + tag);
    assertThatThrownBy(() -> roster.assignShift(site.getId(), List.of(p.getId()), "GS"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("NIGHT or DAY");
    assertThat(people.findById(p.getId()).orElseThrow().getShiftProfile()).isEqualTo("NIGHT");
  }

  @Test
  void somebodyFromAnotherBuildingCannotBeMovedThroughThisOne() {
    IclockSite other = new IclockSite();
    other.setName("Other " + tag);
    other.setTimezone(ShiftConfig.ZONE.getId());
    other = sites.save(other);

    IclockPerson elsewhere = new IclockPerson();
    elsewhere.setSiteId(other.getId());
    elsewhere.setPin(NIGHT_PIN);
    elsewhere.setName("Elsewhere " + tag);
    elsewhere = people.save(elsewhere);

    String id = elsewhere.getId();
    assertThatThrownBy(() -> roster.assignShift(site.getId(), List.of(id), "DAY"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not in this building");
  }

  // -------------------------------------------------------------------- the CSV column

  @Test
  void theCsvColumnAssignsADayShift() {
    roster.applyRosterImport(
        site.getId(), "9001,9001,CSV Day " + tag + ",csvday-" + tag + "@x.test,,,,0,,,,DAY");

    assertThat(people.findBySiteIdAndPin(site.getId(), "9001").orElseThrow().getShiftProfile())
        .isEqualTo("DAY");
  }

  @Test
  void aBlankCsvColumnLeavesAConfirmedAssignmentWhereItIs() {
    // THE FOOTGUN THIS RULE EXISTS FOR. Every other column in this importer overwrites, which is right
    // for fields the terminal export always carries. It does NOT carry a shift, so if blank meant
    // NIGHT, the next routine roster import would quietly undo a confirmed day-shift list and put those
    // people back on the cut that splits their day in half — with nothing in the report to say so.
    IclockPerson p = person("9001", "Confirmed Day " + tag);
    roster.assignShift(site.getId(), List.of(p.getId()), "DAY");

    roster.applyRosterImport(
        site.getId(), "9001,9001,Confirmed Day " + tag + ",cd-" + tag + "@x.test,,,,0,,,,");

    assertThat(people.findBySiteIdAndPin(site.getId(), "9001").orElseThrow().getShiftProfile())
        .as("a blank shift cell means 'not specified', never 'move them to nights'")
        .isEqualTo("DAY");
  }

  @Test
  void aPersonCreatedByImportWithNoShiftCellLandsOnNights() {
    roster.applyRosterImport(
        site.getId(), "9002,9002,Fresh " + tag + ",fresh-" + tag + "@x.test,,,,0,,,,");

    assertThat(people.findBySiteIdAndPin(site.getId(), "9002").orElseThrow().getShiftProfile())
        .isEqualTo("NIGHT");
  }

  @Test
  void anUnrecognisedCsvValueIsTreatedAsUnspecifiedRatherThanMovingAnyone() {
    IclockPerson p = person("9001", "Steady " + tag);
    roster.assignShift(site.getId(), List.of(p.getId()), "DAY");

    // "GS" is the label the terminal report uses. It is not a profile name here, and a stray cell must
    // not move somebody — nor fail the whole import over one word in an optional column.
    roster.applyRosterImport(
        site.getId(), "9001,9001,Steady " + tag + ",steady-" + tag + "@x.test,,,,0,,,,GS");

    assertThat(people.findBySiteIdAndPin(site.getId(), "9001").orElseThrow().getShiftProfile())
        .isEqualTo("DAY");
  }
}
