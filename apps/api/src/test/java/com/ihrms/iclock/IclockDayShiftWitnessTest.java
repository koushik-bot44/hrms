package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * ONE NAMED WITNESS, carried end to end across every shift-aware surface.
 *
 * <p>Priya Day works 10:00&ndash;19:00. Her whole day sits inside the hours a night-shift person is
 * asleep, so every surface that still assumes one global shift gets her visibly wrong — and each of
 * those surfaces is asserted here rather than reasoned about. The night-shift control alongside her is
 * what makes the assertions mean something: if the two ever agree, the profile is decorative.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockDayShiftWitnessTest {

  @Autowired IclockRosterService roster;
  @Autowired IclockReportService reports;
  @Autowired IclockPromotionService promotion;
  @Autowired IclockPersonRepository people;
  @Autowired IclockPunchRepository punches;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockRawPunchRepository rawPunches;
  @Autowired IclockSitePolicyService policies;

  private static final ZoneId IST = ShiftConfig.ZONE;
  /** A Tuesday, so neither the witness nor the control lands on a weekly off. */
  private static final String DAY1 = "2026-09-01";

  /**
   * A day inside the CURRENT payroll cycle, for the recompute tests.
   *
   * <p>Derived rather than fixed, because the recompute window is bounded by {@code YearMonth.now()}
   * and a hard-coded date silently leaves the cycle when the month turns — which is exactly how these
   * two tests started failing on 9 September while the code was correct.
   *
   * <p>Never the 1st. Under the night cut a 10:00 arrival on the 1st files as the last day of the
   * PREVIOUS cycle, so it sits outside the recompute window on purpose; using it here would assert a
   * reach-back that "past cycles untouched" forbids.
   */
  private static String inCycleDay() {
    LocalDate today = LocalDate.now(IST);
    LocalDate cycleStart = today.withDayOfMonth(1);
    LocalDate candidate = today.minusDays(2);
    return (candidate.isAfter(cycleStart) ? candidate : cycleStart.plusDays(1)).toString();
  }

  private IclockSite site;
  private IclockDevice gateIn;
  private IclockDevice gateOut;
  private String tag;
  private IclockPerson priya;
  private IclockPerson raju;

  private static Instant ist(String local) {
    return LocalDateTime.parse(local.replace(' ', 'T')).atZone(IST).toInstant();
  }

  @BeforeEach
  void setUp() {
    tag = UUID.randomUUID().toString().substring(0, 8);
    site = new IclockSite();
    site.setName("Witness Site " + tag);
    site.setTimezone(IST.getId());
    site = sites.save(site);

    gateIn = device("WIN" + tag.toUpperCase(), "GATE", "IN");
    gateOut = device("WOUT" + tag.toUpperCase(), "GATE", "OUT");

    priya = person("4101", "Priya Day " + tag);
    raju = person("4102", "Raju Night " + tag);
  }

  private IclockDevice device(String serial, String area, String dir) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setSiteId(site.getId());
    d.setArea(area);
    d.setDirection(dir);
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

  private void punch(IclockDevice d, String pin, String istLocal) {
    IclockRawPunch raw = new IclockRawPunch();
    raw.setDeviceId(d.getId());
    raw.setSerialNumber(d.getSerialNumber());
    raw.setDevicePin(pin);
    raw.setPunchedAtRaw(istLocal);
    raw.setStatusCode("255");
    raw.setVerifyMode("15");
    raw.setRawLine(pin + "\t" + istLocal + "\t255\t15\t0");
    raw.setLineNumber(1);
    raw.setDedupeKey(IclockDedupe.key(d.getSerialNumber(), raw.getRawLine()));
    promotion.promote(rawPunches.save(raw).getId());
  }

  private List<LocalDate> shiftDatesOf(String personId) {
    return punches.findAll().stream()
        .filter(p -> personId.equals(p.getPersonId()))
        .sorted(java.util.Comparator.comparing(IclockPunch::getEffectiveAt))
        .map(IclockPunch::getShiftDate)
        .toList();
  }

  // ------------------------------------------------------------------ (1) the profile

  @Test
  void theDayProfileIsTenToSevenAndCutsAtHalfPastTwo() {
    var day = policies.effective(site.getId()).profileFor("DAY");
    assertThat(day.start()).isEqualTo(java.time.LocalTime.of(10, 0));
    assertThat(day.end()).isEqualTo(java.time.LocalTime.of(19, 0));
    assertThat(day.dayCut())
        .as("derived from the hours, so correcting the shift moved it from 01:30")
        .isEqualTo(java.time.LocalTime.of(2, 30));
  }

  // ------------------------------------------------------------------ (2) promotion

  @Test
  void herArrivalAndDepartureLandOnOneShiftDay_hisDoNot() {
    roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");

    punch(gateIn, "4101", DAY1 + " 10:00:00");
    punch(gateOut, "4101", DAY1 + " 19:00:00");

    // The control works the same clock hours but stays on nights.
    punch(gateIn, "4102", DAY1 + " 10:00:00");
    punch(gateOut, "4102", DAY1 + " 19:00:00");

    assertThat(shiftDatesOf(priya.getId()))
        .as("the witness pairs")
        .containsExactly(LocalDate.parse(DAY1), LocalDate.parse(DAY1));
    assertThat(shiftDatesOf(raju.getId()))
        .as("THE VACUITY CONTROL: the same two punches straddle two days on the night cut")
        .containsExactly(LocalDate.parse("2026-08-31"), LocalDate.parse(DAY1));
  }

  @Test
  void herOvertimePastMidnightStaysOnTheDayItStarted() {
    roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");
    punch(gateIn, "4101", DAY1 + " 10:00:00");
    punch(gateOut, "4101", "2026-09-02 01:30:00");

    assertThat(shiftDatesOf(priya.getId()))
        .containsExactly(LocalDate.parse(DAY1), LocalDate.parse(DAY1));
  }

  // ------------------------------------------------------------------ (3) the recompute

  @Test
  void reassigningHerRedatesThisCyclesPunchesAndLeavesLastMonthAlone() {
    // She punched a full day BEFORE anybody corrected her shift, so those punches carry the night
    // cut. Without the recompute her board day, her Missing OUT row and her month all stay wrong
    // until her next punch — wrong in a way that looks settled.
    String day = inCycleDay();
    punch(gateIn, "4101", day + " 10:00:00");
    punch(gateOut, "4101", day + " 19:00:00");
    assertThat(shiftDatesOf(priya.getId()))
        .as("filed under the night cut, split")
        .containsExactly(LocalDate.parse(day).minusDays(1), LocalDate.parse(day));

    // A punch from a settled cycle, planted directly so the recompute has something to leave alone.
    IclockRawPunch settledRaw = new IclockRawPunch();
    settledRaw.setDeviceId(gateIn.getId());
    settledRaw.setSerialNumber(gateIn.getSerialNumber());
    settledRaw.setDevicePin("4101");
    settledRaw.setPunchedAtRaw("2026-07-15 10:00:00");
    settledRaw.setStatusCode("255");
    settledRaw.setVerifyMode("15");
    settledRaw.setRawLine("4101\t2026-07-15 10:00:00\t255\t15\t0");
    settledRaw.setLineNumber(1);
    settledRaw.setDedupeKey(IclockDedupe.key(gateIn.getSerialNumber(), settledRaw.getRawLine()));
    settledRaw = rawPunches.saveAndFlush(settledRaw);

    IclockPunch settled = new IclockPunch();
    settled.setRawPunchId(settledRaw.getId());
    settled.setEffectiveRawPunchId(settledRaw.getId());
    settled.setDeviceId(gateIn.getId());
    settled.setSiteId(site.getId());
    settled.setPersonId(priya.getId());
    settled.setDevicePin("4101");
    settled.setPunchedAt(ist("2026-07-15 10:00:00"));
    settled.setEffectiveAt(ist("2026-07-15 10:00:00"));
    settled.setShiftDate(LocalDate.parse("2026-07-14")); // the night cut's answer
    settled.setArea("GATE");
    settled.setDirection("IN");
    settled.setBurstFirstAt(settled.getEffectiveAt());
    settled.setBurstLastAt(settled.getEffectiveAt());
    settled.setBurstCount(1);
    String settledId = punches.saveAndFlush(settled).getId();

    var report = roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");

    assertThat(report.changed()).isEqualTo(1);
    assertThat(report.punchesRedated()).as("the split pair is corrected").isEqualTo(1);
    assertThat(shiftDatesOf(priya.getId()))
        .as("this cycle now pairs")
        .contains(LocalDate.parse(day), LocalDate.parse(day));

    assertThat(punches.findById(settledId).orElseThrow().getShiftDate())
        .as("FORWARD-ONLY: a settled cycle is never re-dated, whatever the new shift says")
        .isEqualTo(LocalDate.parse("2026-07-14"));
  }

  @Test
  void theRecomputeIsIdempotent() {
    String day = inCycleDay();
    punch(gateIn, "4101", day + " 10:00:00");
    punch(gateOut, "4101", day + " 19:00:00");

    var first = roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");
    var again = roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");

    assertThat(first.punchesRedated()).isPositive();
    assertThat(again.changed()).isZero();
    assertThat(again.punchesRedated())
        .as("derived, not incremented — running it twice moves nothing")
        .isZero();
  }

  @Test
  void aPunchAlreadyFILEDInThePreviousCycleIsLeftAloneEvenWhenTheNewShiftDisagrees() {
    // THE DELIBERATE BOUNDARY. A 10:00 arrival on the 1st files as the last day of the previous
    // cycle under the night cut, and the day cut says it belongs to the 1st. The recompute does NOT
    // reach back for it: correcting it would remove a day from a month that may already be paid.
    //
    // The cost is a punch that stays misfiled across a cycle edge. That is the accepted trade against
    // rewriting settled payroll, and it is asserted here so nobody "fixes" it without meeting it.
    LocalDate firstOfCycle = LocalDate.now(IST).withDayOfMonth(1);
    LocalDate previousCycleDay = firstOfCycle.minusDays(1);

    punch(gateIn, "4101", firstOfCycle + " 10:00:00");
    assertThat(shiftDatesOf(priya.getId()))
        .as("the night cut files it into the previous cycle")
        .containsExactly(previousCycleDay);

    var report = roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");

    assertThat(report.changed()).isEqualTo(1);
    assertThat(report.punchesRedated()).as("nothing in a settled cycle is touched").isZero();
    assertThat(shiftDatesOf(priya.getId())).containsExactly(previousCycleDay);
  }

  // ------------------------------------------------------------------ (4) lateness

  @Test
  void sheIsLateAfterTenFifteenAndHeIsNot() {
    roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");
    var day = policies.effective(site.getId()).profileFor("DAY");
    var night = policies.effective(site.getId()).profileFor("NIGHT");
    LocalDate d = LocalDate.parse(DAY1);

    assertThat(day.lateThreshold(d, IST)).isEqualTo(ist(DAY1 + " 10:15:00"));
    assertThat(night.lateThreshold(d, IST)).isEqualTo(ist(DAY1 + " 19:15:00"));

    var onTime = IclockPolicyEngine.day(
        new IclockPolicyEngine.DayInput(d,
            List.of(new IclockDaySessions.Punch(ist(DAY1 + " 10:10:00"), "GATE", "IN"),
                new IclockDaySessions.Punch(ist(DAY1 + " 19:00:00"), "GATE", "OUT")),
            day, 0, false),
        IclockPolicyEngine.Policy.defaults(), IST);
    assertThat(onTime.late()).isFalse();

    var late = IclockPolicyEngine.day(
        new IclockPolicyEngine.DayInput(d,
            List.of(new IclockDaySessions.Punch(ist(DAY1 + " 10:40:00"), "GATE", "IN"),
                new IclockDaySessions.Punch(ist(DAY1 + " 19:00:00"), "GATE", "OUT")),
            day, 0, false),
        IclockPolicyEngine.Policy.defaults(), IST);
    assertThat(late.late()).isTrue();
    assertThat(late.lateMin()).as("measured from 10:00, not from the threshold").isEqualTo(40);
  }

  // ------------------------------------------------------------------ (5) alerts, missing out

  @Test
  void herBreakAlertWindowIsHerWorkingDayNotHisNight() {
    var day = policies.effective(site.getId()).profileFor("DAY");
    var night = policies.effective(site.getId()).profileFor("NIGHT");

    assertThat(day.withinShift(ist(DAY1 + " 14:00:00"), IST)).isTrue();
    assertThat(day.withinShift(ist(DAY1 + " 23:00:00"), IST))
        .as("an alert must not fire for her after she has legitimately gone home")
        .isFalse();
    assertThat(night.withinShift(ist(DAY1 + " 14:00:00"), IST)).isFalse();
    assertThat(night.withinShift(ist(DAY1 + " 23:00:00"), IST)).isTrue();
  }

  @Test
  void theirCompletedShiftDaysDifferAtTheSameInstant() {
    // 21:00 on the 1st: her day finished two hours ago; his has only just begun.
    Instant at = ist(DAY1 + " 21:00:00");
    var day = policies.effective(site.getId()).profileFor("DAY");
    var night = policies.effective(site.getId()).profileFor("NIGHT");

    assertThat(day.completedShiftDay(at, IST)).isEqualTo(LocalDate.parse(DAY1));
    assertThat(night.completedShiftDay(at, IST)).isEqualTo(LocalDate.parse("2026-08-31"));
  }

  // ------------------------------------------------------------------ (6) the report

  @Test
  void theReportCountsHerDayAsOnePresentDayWithNineHours() {
    roster.assignShift(site.getId(), List.of(priya.getId()), "DAY");
    punch(gateIn, "4101", DAY1 + " 10:00:00");
    punch(gateOut, "4101", DAY1 + " 19:00:00");

    var report = reports.monthly(site.getId(), YearMonth.parse("2026-09"));
    var her = report.rows().stream()
        .filter(r -> "4101".equals(r.pin())).findFirst().orElseThrow();

    assertThat(her.shiftProfile()).isEqualTo("DAY");
    assertThat(her.presentDays()).as("one day, not two halves").isEqualTo(1);
    assertThat(her.workedMin()).as("a full nine-hour shift").isEqualTo(540);
    assertThat(her.lateDays()).isZero();
    assertThat(her.daysWithMissingPunch())
        .as("both ends of her day were seen, so nothing is estimated")
        .isZero();
  }

  @Test
  void vacuityControl_theNightShifterWorkingHerHoursLooksBroken() {
    // The same clock hours on the night profile: the day splits, so one half has no OUT and the
    // other no IN. If this ever stops failing that way, the profile has stopped mattering.
    punch(gateIn, "4102", DAY1 + " 10:00:00");
    punch(gateOut, "4102", DAY1 + " 19:00:00");

    var report = reports.monthly(site.getId(), YearMonth.parse("2026-09"));
    var him = report.rows().stream()
        .filter(r -> "4102".equals(r.pin())).findFirst().orElseThrow();

    // His 10:00 arrival files on 31 August under the 11:30 night cut, so SEPTEMBER only ever sees the
    // 19:00 departure — a whole day of work reduced to an orphan exit in the wrong month. Her
    // identical punches produce one clean present day; that is the difference the profile makes.
    assertThat(shiftDatesOf(raju.getId()))
        .containsExactly(LocalDate.parse("2026-08-31"), LocalDate.parse(DAY1));
    assertThat(him.presentDays()).as("only the half that landed in September").isEqualTo(1);
    assertThat(him.workedMin())
        .as("an exit with no arrival is worth nothing — never guessed at")
        .isZero();
  }
}
