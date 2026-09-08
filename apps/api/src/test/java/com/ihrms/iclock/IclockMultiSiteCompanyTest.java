package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.IclockEmployeePin;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.IclockEmployeePinRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockSiteCompanyRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.iclock.dto.IclockAdminDtos.AssignPinRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

/**
 * A company may work in more than one building (V49) — and the pins of the building it was ALREADY in
 * must not move when it joins a second.
 *
 * <p>That second sentence is the whole reason the migration and the code shipped together. An
 * adversarial review correctly called the {@code repointCompanyPins} hazard unreachable: under D2,
 * {@code linkCompany} refused the second building before ever reaching the UPDATE. Removing that
 * refusal is the entire point of this change, which is exactly what makes the hazard live. Half of
 * this change would have been worse than neither half.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockMultiSiteCompanyTest {

  @Autowired IclockAdminService admin;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockSiteCompanyRepository siteCompanies;
  @Autowired IclockEmployeePinRepository pins;
  @Autowired IclockPersonRepository people;
  @Autowired CompanyRepository companies;
  @Autowired EmployeeRepository employees;
  @Autowired UserRepository users;

  private String tag;
  private String orion;
  private String building9;
  private Company screatives;

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    orion = site("Orion " + tag);
    building9 = site("Building9 " + tag);
    screatives = company("Screatives " + tag);
  }

  private String site(String name) {
    IclockSite s = new IclockSite();
    s.setName(name);
    s.setTimezone(ShiftConfig.ZONE.getId());
    return sites.save(s).getId();
  }

  private Company company(String name) {
    Company c = new Company();
    c.setName(name);
    // The TAG has to survive into the code: truncating to 10 chars cut it off, so every run produced
    // the same code and collided on companies_code_key.
    c.setCode(("C" + tag + UUID.randomUUID().toString().substring(0, 4)).toUpperCase());
    c.setStatus("ACTIVE");
    return companies.save(c);
  }

  private Employee employee(String name, Company c) {
    User hr = new User();
    hr.setEmail("hr-" + UUID.randomUUID() + "@x.test");
    hr.setName("HR");
    hr.setRole(UserRole.HR);
    hr.setCompanyId(c.getId());
    hr = users.save(hr);

    Employee e = new Employee();
    e.setOnboardingHrId(hr.getId());
    e.setFullName(name);
    e.setEmail(name.replaceAll("\\s+", "").toLowerCase() + "-" + UUID.randomUUID() + "@x.test");
    e.setDesignation("Engineer");
    e.setDateOfJoining(LocalDate.parse("2026-01-01"));
    e.setCompanyId(c.getId());
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private IclockEmployeePin pin(Employee e, String siteId, String pin) {
    IclockEmployeePin p = new IclockEmployeePin();
    p.setEmployeeId(e.getId());
    p.setSiteId(siteId);
    p.setPin(pin);
    return pins.save(p);
  }

  // ------------------------------------------------------------ the widening

  @Test
  void oneCompanyCanBeLinkedToTwoBuildings() {
    admin.linkCompany(orion, screatives.getId());
    assertThatCode(() -> admin.linkCompany(building9, screatives.getId()))
        .as("D2 is over: Screatives genuinely has staff in both buildings")
        .doesNotThrowAnyException();

    assertThat(siteCompanies.findByCompanyId(screatives.getId()))
        .extracting(sc -> sc.getSiteId())
        .containsExactlyInAnyOrder(orion, building9);
  }

  @Test
  void relinkingTheSameBuildingStaysIdempotent() {
    admin.linkCompany(orion, screatives.getId());
    admin.linkCompany(orion, screatives.getId());
    assertThat(siteCompanies.findByCompanyId(screatives.getId())).hasSize(1);
  }

  // ------------------------------------------- THE HAZARD THIS UNIT EXISTS FOR

  @Test
  void linkingASecondBuildingDoesNotTouchTheFirstBuildingsPins() {
    // The save of the survey. With no site filter this UPDATE dragged every Orion pin into Building 9
    // — silently if no collision, or as a unique violation that rolls back a legitimate operation.
    admin.linkCompany(orion, screatives.getId());
    Employee alice = employee("Alice " + tag, screatives);
    IclockEmployeePin alicePin = pin(alice, orion, "7001");

    admin.linkCompany(building9, screatives.getId());

    assertThat(pins.findById(alicePin.getId()).orElseThrow().getSiteId())
        .as("her pin belongs where it already was; the company ADDED a building, it did not move")
        .isEqualTo(orion);
  }

  @Test
  void vacuityNegative_aCollidingPinWouldHaveBlownUpUnderTheOldBlanketUpdate() {
    // Same shape, but with a pin at Building 9 that shares Alice's number. Under the old blanket
    // UPDATE this link would have violated UNIQUE(siteId, pin) and 500'd. It must simply succeed.
    admin.linkCompany(orion, screatives.getId());
    Employee alice = employee("Alice " + tag, screatives);
    IclockEmployeePin orionPin = pin(alice, orion, "7002");

    Employee bob = employee("Bob " + tag, screatives);
    IclockEmployeePin b9Pin = pin(bob, building9, "7002");

    assertThatCode(() -> admin.linkCompany(building9, screatives.getId()))
        .doesNotThrowAnyException();

    assertThat(pins.findById(orionPin.getId()).orElseThrow().getSiteId()).isEqualTo(orion);
    assertThat(pins.findById(b9Pin.getId()).orElseThrow().getSiteId()).isEqualTo(building9);
  }

  // ------------------------------------------------------------------ unlink

  @Test
  void unlinkingOneBuildingIsNotBlockedByTheOthersPins() {
    // The pin guard was company-wide, so once a company spanned two sites its pins in one blocked
    // unlinking it from the other — and with the lookup also company-wide there was no way out at all.
    admin.linkCompany(orion, screatives.getId());
    admin.linkCompany(building9, screatives.getId());
    Employee alice = employee("Alice " + tag, screatives);
    pin(alice, orion, "7003");

    assertThatCode(() -> admin.unlinkCompany(building9, screatives.getId()))
        .as("Building 9 has none of this company's pins")
        .doesNotThrowAnyException();

    assertThat(siteCompanies.findByCompanyId(screatives.getId()))
        .extracting(sc -> sc.getSiteId())
        .containsExactly(orion);
  }

  @Test
  void unlinkingIsStillRefusedWhileThatBuildingHoldsPins() {
    admin.linkCompany(orion, screatives.getId());
    Employee alice = employee("Alice " + tag, screatives);
    pin(alice, orion, "7004");

    assertThatThrownBy(() -> admin.unlinkCompany(orion, screatives.getId()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("mapped pin");
  }

  // ------------------------------------------------------------------ assignPin

  @Test
  void anEmployeeMayHoldAPinInEitherBuildingTheirCompanyWorksIn() {
    admin.linkCompany(orion, screatives.getId());
    admin.linkCompany(building9, screatives.getId());
    Employee alice = employee("Alice " + tag, screatives);

    assertThatCode(() -> admin.assignPin(building9, new AssignPinRequest(alice.getId(), "7005")))
        .as("the question is 'is the company at THIS building', not 'which one building'")
        .doesNotThrowAnyException();
  }

  @Test
  void anEmployeeStillCannotGetAPinWhereTheirCompanyDoesNotWork() {
    admin.linkCompany(orion, screatives.getId());
    Employee alice = employee("Alice " + tag, screatives);

    assertThatThrownBy(() -> admin.assignPin(building9, new AssignPinRequest(alice.getId(), "7006")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("not linked to this building");
  }

  // -------------------------------------------------- one employee, two rosters

  @Test
  void oneEmployeeCanBeLinkedToARosterPersonInEachBuilding() {
    // V49 widened iclock_people's employeeId unique from global to per-site. The global form made the
    // dual-site movers — the exact people this change is for — the ones who could not be linked.
    Employee alice = employee("Alice " + tag, screatives);

    IclockPerson atOrion = new IclockPerson();
    atOrion.setSiteId(orion);
    atOrion.setPin("8001");
    atOrion.setEmployeeId(alice.getId());
    people.saveAndFlush(atOrion);

    IclockPerson atB9 = new IclockPerson();
    atB9.setSiteId(building9);
    atB9.setPin("8001");
    atB9.setEmployeeId(alice.getId());

    assertThatCode(() -> people.saveAndFlush(atB9))
        .as("same human, two buildings, two roster rows")
        .doesNotThrowAnyException();
  }

  @Test
  void butStillOnlyOnceWITHINABuilding() {
    // The ambiguity the original index actually protected against: two rows in one building pointing
    // at one employee would make "whose punch is this" unanswerable.
    Employee alice = employee("Alice " + tag, screatives);

    IclockPerson first = new IclockPerson();
    first.setSiteId(orion);
    first.setPin("8002");
    first.setEmployeeId(alice.getId());
    people.saveAndFlush(first);

    IclockPerson second = new IclockPerson();
    second.setSiteId(orion);
    second.setPin("8003");
    second.setEmployeeId(alice.getId());

    assertThatThrownBy(() -> people.saveAndFlush(second))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }
}
