package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockEmployeePin;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.model.IclockSiteCompany;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockEmployeePinRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteCompanyRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Burst collapse, against a real database.
 *
 * <p>Every scenario the design has to survive is here, including the two that actually bit in
 * production: the buffered flush that delivered ~10 minutes of punches out of order, and the need for
 * an effective row to exist from the FIRST punch rather than after a settling delay.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockBurstCollapseTest {

  @Autowired private IclockPromotionService promotion;
  @Autowired private IclockRawPunchRepository rawPunches;
  @Autowired private IclockPunchRepository punches;
  @Autowired private IclockDeviceRepository devices;
  @Autowired private IclockSiteRepository sites;
  @Autowired private IclockSiteCompanyRepository siteCompanies;
  @Autowired private IclockEmployeePinRepository pins;
  @Autowired private IclockPersonRepository people;
  @Autowired private EmployeeRepository employees;
  @Autowired private CompanyRepository companies;
  @Autowired private UserRepository users;

  private IclockSite site;
  private Employee employee;
  private String pin;
  private IclockDevice gateIn;
  private IclockDevice gateOut;

  private String uniq() {
    return UUID.randomUUID().toString().substring(0, 8);
  }

  @BeforeEach
  void setUp() {
    String tag = uniq();
    site = new IclockSite();
    site.setName("Site " + tag);
    site.setTimezone(ShiftConfig.ZONE.getId());
    site = sites.save(site);

    Company company = new Company();
    company.setName("Co " + tag);
    company.setCode("CO" + tag.toUpperCase());
    company = companies.save(company);

    IclockSiteCompany link = new IclockSiteCompany();
    link.setSiteId(site.getId());
    link.setCompanyId(company.getId());
    siteCompanies.save(link);

    // employees.onboardingHrId is NOT NULL, so the fixture needs a real HR user to hang off.
    User hr = new User();
    hr.setEmail("hr-" + tag + "@example.test");
    hr.setName("HR " + tag);
    hr.setRole(UserRole.HR);
    hr.setCompanyId(company.getId());
    hr = users.save(hr);

    employee = new Employee();
    employee.setOnboardingHrId(hr.getId());
    employee.setFullName("Burst Tester " + tag);
    employee.setEmail("burst-" + tag + "@example.test");
    employee.setDesignation("Engineer");
    employee.setDateOfJoining(LocalDate.parse("2026-01-01"));
    employee.setCompanyId(company.getId());
    employee.setStatus(EmployeeStatus.APPROVED);
    employee = employees.save(employee);

    pin = String.valueOf(100000 + Math.abs(tag.hashCode() % 800000));
    IclockEmployeePin mapping = new IclockEmployeePin();
    mapping.setEmployeeId(employee.getId());
    mapping.setPin(pin);
    mapping.setSiteId(site.getId());
    pins.save(mapping);

    // P1b: the ROSTER is the sole resolution source. Under P1a this fixture stopped at the pin
    // mapping above, and promotion joined pin -> employee_pins -> employees; it now goes
    // pin -> iclock_people, so a fixture without a person resolves to UNKNOWN_PIN and every
    // scenario below silently stops testing burst collapse at all.
    //
    // The employee link is set here because these tests assert on the punch's employeeId, but note
    // that it is ENRICHMENT: the same scenarios pass with employeeId null, which is the majority
    // case in production. The employee_pins row is kept so the P1a mapping surface stays exercised.
    IclockPerson person = new IclockPerson();
    person.setSiteId(site.getId());
    person.setPin(pin);
    person.setName(employee.getFullName());
    person.setEmail(employee.getEmail());
    person.setCompanyId(company.getId());
    person.setEmployeeId(employee.getId());
    people.save(person);

    gateIn = claimedDevice("IN-" + tag, "GATE", "IN");
    gateOut = claimedDevice("OUT-" + tag, "GATE", "OUT");
  }

  private IclockDevice claimedDevice(String serial, String area, String direction) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setSiteId(site.getId());
    d.setArea(area);
    d.setDirection(direction);
    d.setClaimedAt(Instant.now());
    d.setStatus("CLAIMED");
    return devices.save(d);
  }

  /** Persists a raw punch and promotes it, returning the outcome. */
  private IclockPromotionService.Outcome punch(IclockDevice device, String istLocalTime) {
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
    raw = rawPunches.save(raw);
    return promotion.promote(raw.getId());
  }

  private List<IclockPunch> effective(IclockDevice device) {
    return punches.findBySiteIdAndShiftDateOrderByEffectiveAtAsc(
            site.getId(), LocalDate.parse("2026-08-25"))
        .stream()
        .filter(p -> p.getDeviceId().equals(device.getId()))
        .toList();
  }

  private static Instant ist(String local) {
    return LocalDateTime.parse(local.replace(' ', 'T')).atZone(ShiftConfig.ZONE).toInstant();
  }

  // ------------------------------------------------------------------- (1)

  @Test
  void inBurstKeepsTheFirstPunchAndIsVisibleImmediately() {
    assertThat(punch(gateIn, "2026-08-25 19:00:00")).isEqualTo(IclockPromotionService.Outcome.PROMOTED_NEW);

    // Visible after the FIRST punch — no settling delay. This is the live-visibility requirement.
    assertThat(effective(gateIn)).hasSize(1);

    punch(gateIn, "2026-08-25 19:00:20");
    punch(gateIn, "2026-08-25 19:00:45");
    punch(gateIn, "2026-08-25 19:01:10");
    punch(gateIn, "2026-08-25 19:01:30");

    List<IclockPunch> rows = effective(gateIn);
    assertThat(rows).hasSize(1);
    IclockPunch p = rows.get(0);
    assertThat(p.getBurstCount()).isEqualTo(5);
    assertThat(p.getEffectiveAt()).isEqualTo(ist("2026-08-25 19:00:00")); // IN keeps the FIRST
    assertThat(p.getBurstFirstAt()).isEqualTo(ist("2026-08-25 19:00:00"));
    assertThat(p.getBurstLastAt()).isEqualTo(ist("2026-08-25 19:01:30"));
  }

  // ------------------------------------------------------------------- (2)

  @Test
  void outBurstKeepsTheLatestAndUpdatesInPlaceAsItExtends() {
    punch(gateOut, "2026-08-26 03:00:00");
    assertThat(effective(gateOut)).hasSize(1);
    assertThat(effective(gateOut).get(0).getEffectiveAt()).isEqualTo(ist("2026-08-26 03:00:00"));

    punch(gateOut, "2026-08-26 03:00:40");
    punch(gateOut, "2026-08-26 03:01:15");

    List<IclockPunch> rows = effective(gateOut);
    assertThat(rows).hasSize(1);
    // OUT keeps the LATEST, and the row was UPDATED rather than replaced.
    assertThat(rows.get(0).getEffectiveAt()).isEqualTo(ist("2026-08-26 03:01:15"));
    assertThat(rows.get(0).getBurstCount()).isEqualTo(3);
  }

  // ------------------------------------------------------------------- (3)

  @Test
  void aPunchOnAnotherDeviceBreaksTheChain() {
    punch(gateIn, "2026-08-25 19:00:00");
    punch(gateOut, "2026-08-25 19:00:30"); // went out in between
    punch(gateIn, "2026-08-25 19:01:00"); // came back: a NEW presentation, not a re-tap

    assertThat(effective(gateIn)).hasSize(2);
    assertThat(effective(gateOut)).hasSize(1);
  }

  // ------------------------------------------------------------------- (4)

  @Test
  void reRunningPromotionChangesNothing() {
    punch(gateIn, "2026-08-25 19:00:00");
    punch(gateIn, "2026-08-25 19:00:30");

    IclockPunch before = effective(gateIn).get(0);
    int countBefore = before.getBurstCount();
    Instant updatedBefore = before.getUpdatedAt();

    for (IclockRawPunch raw : rawPunches.findBySerialNumberOrderByReceivedAtDesc(gateIn.getSerialNumber())) {
      assertThat(promotion.promote(raw.getId()))
          .isEqualTo(IclockPromotionService.Outcome.ALREADY_PROMOTED);
    }

    IclockPunch after = effective(gateIn).get(0);
    assertThat(after.getBurstCount()).isEqualTo(countBefore); // no double-counting
    assertThat(after.getUpdatedAt()).isEqualTo(updatedBefore); // no UPDATE churn at all
  }

  // ------------------------------------------------------------------- (5)

  @Test
  void outOfOrderArrivalProducesTheSameResultAsInOrder() {
    // The production case: a buffered flush delivered punches whose arrival order did not match their
    // punch order. Feeding them backwards must still yield one burst with the same edges.
    List<String> times =
        new ArrayList<>(
            List.of(
                "2026-08-25 19:00:00",
                "2026-08-25 19:00:25",
                "2026-08-25 19:00:50",
                "2026-08-25 19:01:15"));
    Collections.reverse(times);
    for (String t : times) {
      punch(gateIn, t);
    }

    List<IclockPunch> rows = effective(gateIn);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).getBurstCount()).isEqualTo(4);
    assertThat(rows.get(0).getBurstFirstAt()).isEqualTo(ist("2026-08-25 19:00:00"));
    assertThat(rows.get(0).getBurstLastAt()).isEqualTo(ist("2026-08-25 19:01:15"));
    // IN keeps the earliest even though it arrived LAST.
    assertThat(rows.get(0).getEffectiveAt()).isEqualTo(ist("2026-08-25 19:00:00"));
  }

  // ------------------------------------------------------------------- (6)

  @Test
  void mixedDeviceDoesNotCollapseAndIsFlagged() {
    IclockDevice mixed = claimedDevice("MIX-" + uniq(), "GATE", "MIXED");
    punch(mixed, "2026-08-25 19:00:00");
    punch(mixed, "2026-08-25 19:00:20");

    List<IclockPunch> rows = effective(mixed);
    assertThat(rows).hasSize(2); // no collapse — MIXED carries no usable direction
    assertThat(rows).allMatch(p -> "MIXED_NO_COLLAPSE".equals(p.getAnomaly()));
  }

  // ------------------------------------------------------------------- (7)

  @Test
  void cafeteriaPunchesNeverBreakAGateBurst() {
    IclockDevice cafeteria = claimedDevice("CAF-" + uniq(), "CAFETERIA", "IN");

    punch(gateIn, "2026-08-25 19:00:00");
    punch(cafeteria, "2026-08-25 19:00:30"); // lunch, mid-burst
    punch(gateIn, "2026-08-25 19:01:00");

    // The gate burst is untouched by the cafeteria punch: same-area only breaks a chain.
    assertThat(effective(gateIn)).hasSize(1);
    assertThat(effective(gateIn).get(0).getBurstCount()).isEqualTo(2);
    assertThat(effective(cafeteria)).hasSize(1);
  }

  // ---------------------------------------------------------- shift-day cut

  @Test
  void shiftDateFollowsTheKeptPunchAcrossTheFourAmCut() {
    // An OUT burst straddling 04:00: the kept punch is the LATEST, so the shift-day must follow it.
    punch(gateOut, "2026-08-26 03:59:30");
    punch(gateOut, "2026-08-26 04:00:30");

    List<IclockPunch> all = punches.findByEmployeeIdAndShiftDateOrderByEffectiveAtAsc(
        employee.getId(), LocalDate.parse("2026-08-26"));
    assertThat(all).hasSize(1);
    assertThat(all.get(0).getEffectiveAt()).isEqualTo(ist("2026-08-26 04:00:30"));
  }

  // ------------------------------------------------------------- resolution

  @Test
  void anUnclaimedDeviceDeclinesToPromoteAndLeavesThePunchRaw() {
    IclockDevice unclaimed = new IclockDevice();
    unclaimed.setSerialNumber("UNCLAIMED-" + uniq());
    unclaimed = devices.save(unclaimed);

    assertThat(punch(unclaimed, "2026-08-25 19:00:00"))
        .isEqualTo(IclockPromotionService.Outcome.DEVICE_UNCLAIMED);
    assertThat(effective(unclaimed)).isEmpty();
  }

  @Test
  void anUnknownPinDeclinesToPromote() {
    IclockRawPunch raw = new IclockRawPunch();
    raw.setDeviceId(gateIn.getId());
    raw.setSerialNumber(gateIn.getSerialNumber());
    raw.setDevicePin("999999999");
    raw.setPunchedAtRaw("2026-08-25 19:00:00");
    raw.setRawLine("999999999\t2026-08-25 19:00:00\t255\t15\t0");
    raw.setLineNumber(1);
    raw.setDedupeKey(IclockDedupe.key(gateIn.getSerialNumber(), raw.getRawLine()));
    raw = rawPunches.save(raw);

    assertThat(promotion.promote(raw.getId()))
        .isEqualTo(IclockPromotionService.Outcome.UNKNOWN_PIN);
  }

  @Test
  void aPaddedDevicePinStillResolvesToTheCanonicalMapping() {
    // The live fleet sends 000261 for a pin stored as 261 — D3's whole reason for existing.
    IclockRawPunch raw = new IclockRawPunch();
    raw.setDeviceId(gateIn.getId());
    raw.setSerialNumber(gateIn.getSerialNumber());
    raw.setDevicePin("00000" + pin); // zero-padded on the wire
    raw.setPunchedAtRaw("2026-08-25 20:00:00");
    raw.setRawLine("00000" + pin + "\t2026-08-25 20:00:00\t255\t15\t0");
    raw.setLineNumber(1);
    raw.setDedupeKey(IclockDedupe.key(gateIn.getSerialNumber(), raw.getRawLine()));
    raw = rawPunches.save(raw);

    assertThat(promotion.promote(raw.getId()).promoted()).isTrue();
  }
}
