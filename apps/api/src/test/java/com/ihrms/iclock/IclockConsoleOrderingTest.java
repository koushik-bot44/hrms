package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.IclockDevice;
import com.ihrms.domain.model.IclockPerson;
import com.ihrms.domain.model.IclockPunch;
import com.ihrms.domain.model.IclockPunchMember;
import com.ihrms.domain.model.IclockRawPunch;
import com.ihrms.domain.model.IclockSite;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.IclockDeviceRepository;
import com.ihrms.domain.repository.IclockPersonRepository;
import com.ihrms.domain.repository.IclockPunchMemberRepository;
import com.ihrms.domain.repository.IclockPunchRepository;
import com.ihrms.domain.repository.IclockRawPunchRepository;
import com.ihrms.domain.repository.IclockSiteRepository;
import com.ihrms.iclock.dto.IclockAdminDtos.DeviceView;
import com.ihrms.iclock.dto.IclockRosterDtos.PersonView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The console's ORDERING contract (recency addendum), asserted per surface.
 *
 * <p>The rule: every time-stamped listing defaults to newest-first — Live Board columns, the inbox
 * action list, device last-contact. Explicitly exempt: the person day-grid stays chronological, and the
 * People directory stays name-ordered with sortable columns.
 *
 * <p>Ordering is exactly the kind of behaviour that regresses without anyone noticing: nothing throws,
 * no count changes, the screen just quietly stops answering the question it was arranged to answer.
 * Before this class, three of the four surfaces were ordered by accident — the board by roster pin
 * order, the inbox by volume, and the device list by nothing at all (raw {@code findAll()}, which is
 * database row order and not even stable between calls).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class IclockConsoleOrderingTest {

  @Autowired IclockBoardService board;
  @Autowired IclockRosterService roster;
  @Autowired IclockAdminService admin;
  @Autowired IclockSiteRepository sites;
  @Autowired IclockDeviceRepository devices;
  @Autowired IclockPersonRepository people;
  @Autowired IclockPunchRepository punches;
  @Autowired IclockPunchMemberRepository members;
  @Autowired IclockRawPunchRepository rawPunches;
  @Autowired CompanyRepository companies;
  @Autowired JdbcTemplate jdbc;

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final DateTimeFormatter WIRE =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(IST);

  private String siteId;
  private String deviceId;
  private String companyId;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"iclock_punch_members\",\"iclock_punches\",\"iclock_raw_punches\","
            + "\"iclock_people\",\"iclock_employee_pins\",\"iclock_devices\","
            + "\"iclock_site_companies\",\"iclock_sites\",\"users\",\"employees\",\"companies\","
            + "\"teams\",\"audit_logs\",\"employee_code_sequences\" RESTART IDENTITY CASCADE");

    IclockSite site = new IclockSite();
    site.setName("Ordering Test Building");
    siteId = sites.save(site).getId();

    Company c = new Company();
    c.setName("Zebra Industries");
    c.setCode("ZI");
    companyId = companies.save(c).getId();

    IclockDevice d = new IclockDevice();
    d.setSerialNumber("ZZTESTORD1");
    d.setStatus("CLAIMED");
    d.setSiteId(siteId);
    d.setArea("GATE");
    d.setDirection("IN");
    d.setClaimedAt(Instant.now().minusSeconds(86_400));
    deviceId = devices.save(d).getId();
  }

  // ------------------------------------------------------------------ live board

  @Test
  void liveBoardColumnsPutTheLatestPunchAtTheTop() {
    // Deliberately created in an order that does NOT match their punch times, and with names that sort
    // the opposite way, so a test that passed on insertion order or on name would fail here.
    String early = person("1001", "Aaron Early");
    String late = person("1002", "Zoe Late");
    String middle = person("1003", "Mia Middle");

    LocalDate day = shiftDayNow();
    punchFor(early, "1001", day, minutesAgo(90));
    punchFor(late, "1002", day, minutesAgo(5));
    punchFor(middle, "1003", day, minutesAgo(40));

    List<IclockBoardService.PersonChip> inOffice = board.board(siteId).inOffice();

    assertThat(inOffice).extracting(IclockBoardService.PersonChip::name)
        .containsExactly("Zoe Late", "Mia Middle", "Aaron Early");
  }

  @Test
  void liveBoardCarriesTheCompanyNameSoItCanBeGroupedByCompany() {
    // The board used to pass a literal null here, which made "group the board by company" a backend
    // gap rather than a presentation one.
    String withCompany = person("2001", "Has Company");
    IclockPerson p = people.findById(withCompany).orElseThrow();
    p.setCompanyId(companyId);
    people.save(p);

    punchFor(withCompany, "2001", shiftDayNow(), minutesAgo(10));

    assertThat(board.board(siteId).inOffice())
        .extracting(IclockBoardService.PersonChip::companyName)
        .containsExactly("Zebra Industries");
  }

  @Test
  void aPersonWhoseCompanyLabelNeverMatchedStillGroupsSomewhere() {
    // An unmatched seed label is real — V44 says so — and grouping it under its label beats dropping
    // the person into a nameless bucket.
    String p = person("2002", "Unmatched Co");
    IclockPerson person = people.findById(p).orElseThrow();
    person.setCompanyId(null);
    person.setCompanyLabel("Ghost Systems");
    people.save(person);

    punchFor(p, "2002", shiftDayNow(), minutesAgo(10));

    assertThat(board.board(siteId).inOffice())
        .extracting(IclockBoardService.PersonChip::companyName)
        .containsExactly("Ghost Systems");
  }

  @Test
  void theNotArrivedColumnFallsBackToNameBecauseNobodyInItHasAPunch() {
    person("3003", "Charlie");
    person("3001", "alice");
    person("3002", "Bob");

    assertThat(board.board(siteId).notArrived())
        .extracting(IclockBoardService.PersonChip::name)
        .containsExactly("alice", "Bob", "Charlie"); // case-insensitive, not ASCII order
  }

  // ---------------------------------------------------------------------- inbox

  @Test
  void theInboxLeadsWithTheMostRecentlyPunchingPinNotTheLoudestOne() {
    // The pin with the MOST punches is not the one that needs attention — the one punching NOW is.
    // The inbox was ordered by volume, so a noisy archive pin outranked somebody at the gate.
    for (int i = 0; i < 20; i++) {
      unattributedRaw("7777", minutesAgo(600 + i));
    }
    unattributedRaw("8888", minutesAgo(3));

    var inbox = roster.inboxFor(siteId, 50);

    assertThat(inbox.live()).isNotEmpty();
    assertThat(inbox.live().get(0).pin())
        .as("the pin that punched 3 minutes ago outranks the one with 20 old punches")
        .isEqualTo("8888");
    assertThat(inbox.live().get(0).punchCount()).isEqualTo(1);
  }

  // -------------------------------------------------------------------- devices

  @Test
  void devicesAreOrderedByLastContactAndAreDeterministic() {
    IclockDevice quiet = extraDevice("ZZTESTORD2", minutesAgo(240));
    IclockDevice recent = extraDevice("ZZTESTORD3", minutesAgo(2));
    IclockDevice never = extraDevice("ZZTESTORD4", null);
    devices.findById(deviceId).ifPresent(d -> {
      d.setLastSeenAt(minutesAgo(60));
      devices.save(d);
    });

    List<DeviceView> first = admin.listDevices(null);
    List<DeviceView> second = admin.listDevices(null);

    assertThat(first).extracting(DeviceView::serialNumber)
        .containsExactly(recent.getSerialNumber(), "ZZTESTORD1", quiet.getSerialNumber(),
            never.getSerialNumber());
    // "Never seen" sorts LAST — it is not "just seen".
    assertThat(first.get(first.size() - 1).serialNumber()).isEqualTo(never.getSerialNumber());
    // Deterministic: raw findAll() returned database row order and could differ between calls.
    assertThat(second).extracting(DeviceView::serialNumber)
        .isEqualTo(first.stream().map(DeviceView::serialNumber).toList());
  }

  // --------------------------------------------------------------------- people

  @Test
  void thePeopleDirectoryIsNameOrderedWithUnnamedLast() {
    // EXEMPT from newest-first by the addendum: a directory is looked up, not watched. It used to be
    // ordered by pin, which is an artefact of the import — nobody looks a colleague up by pin.
    person("9003", "Charlie Third");
    person("9001", "alice first"); // lowercase: ordering must be case-insensitive
    person("9002", "Bob Second");
    person("9004", null); // unnamed

    List<PersonView> listed = roster.listPeople(siteId);

    assertThat(listed).extracting(PersonView::name)
        .containsExactly("alice first", "Bob Second", "Charlie Third", null);
  }

  // ------------------------------------------------------------------- fixtures

  private LocalDate shiftDayNow() {
    return IclockShiftDay.of(Instant.now(), IST);
  }

  private static Instant minutesAgo(long m) {
    return Instant.now().minusSeconds(m * 60);
  }

  private String person(String pin, String name) {
    IclockPerson p = new IclockPerson();
    p.setSiteId(siteId);
    p.setPin(pin);
    p.setName(name);
    return people.save(p).getId();
  }

  private IclockDevice extraDevice(String serial, Instant lastSeen) {
    IclockDevice d = new IclockDevice();
    d.setSerialNumber(serial);
    d.setStatus("CLAIMED");
    d.setSiteId(siteId);
    d.setArea("GATE");
    d.setDirection("IN");
    d.setClaimedAt(Instant.now().minusSeconds(86_400));
    d.setLastSeenAt(lastSeen);
    return devices.save(d);
  }

  /** A promoted punch for one person — raw row, effective punch and the membership that links them. */
  private void punchFor(String personId, String pin, LocalDate day, Instant at) {
    IclockRawPunch r = new IclockRawPunch();
    r.setDeviceId(deviceId);
    r.setSerialNumber("ZZTESTORD1");
    r.setDevicePin(pin);
    r.setPunchedAtRaw(WIRE.format(at));
    r.setRawLine("ord-" + pin + "-" + at);
    r.setLineNumber(1);
    r.setDedupeKey("ord-" + pin + "-" + at);
    IclockRawPunch raw = rawPunches.save(r);

    IclockPunch p = new IclockPunch();
    p.setRawPunchId(raw.getId());
    p.setEffectiveRawPunchId(raw.getId());
    p.setDeviceId(deviceId);
    p.setSiteId(siteId);
    p.setPersonId(personId);
    p.setDevicePin(pin);
    p.setPunchedAt(at);
    p.setEffectiveAt(at);
    p.setShiftDate(day);
    p.setArea("GATE");
    p.setDirection("IN");
    p.setBurstFirstAt(at);
    p.setBurstLastAt(at);
    p.setBurstCount(1);
    IclockPunch saved = punches.save(p);

    IclockPunchMember m = new IclockPunchMember();
    m.setPunchId(saved.getId());
    m.setRawPunchId(raw.getId());
    members.save(m);
  }

  /** A raw punch with no effective row — what the inbox is built from. */
  private void unattributedRaw(String pin, Instant at) {
    IclockRawPunch r = new IclockRawPunch();
    r.setDeviceId(deviceId);
    r.setSerialNumber("ZZTESTORD1");
    r.setDevicePin(pin);
    r.setPunchedAtRaw(WIRE.format(at));
    r.setRawLine("unattr-" + pin + "-" + at);
    r.setLineNumber(1);
    r.setDedupeKey("unattr-" + pin + "-" + at);
    IclockRawPunch saved = rawPunches.save(r);
    jdbc.update(
        "UPDATE \"iclock_raw_punches\" SET \"receivedAt\" = ? WHERE \"id\" = ?",
        java.sql.Timestamp.from(at), saved.getId());
  }
}
