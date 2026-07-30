package com.ihrms.accountant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.attendance.AttendanceCalendar;
import com.ihrms.attendance.ShiftConfig;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.enums.LeaveType;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.AttendanceBreak;
import com.ihrms.domain.model.AttendanceSession;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.LeaveRequest;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.AttendanceBreakRepository;
import com.ihrms.domain.repository.AttendanceSessionRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.LeaveRequestRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Read-only attendance analytics for the viewer roles (§8a/§2). Seeds a real month of sessions/breaks/
 * leaves — incl. an overnight session whose SHIFT-DAY lands in a different calendar month than its clock-in,
 * a late session, an open session, a break, and approved leaves clipped to the month — and asserts the live
 * metrics + the ACCOUNTS_ADMIN (any) / ACCOUNTANT (own-team-only) scoping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ViewerAttendanceApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired EmployeeRepository employees;
  @Autowired TeamRepository teams;
  @Autowired AttendanceSessionRepository sessions;
  @Autowired AttendanceBreakRepository breaks;
  @Autowired LeaveRequestRepository leaves;
  @Autowired JdbcTemplate jdbc;

  private String companyA;
  private Employee empA; // approved, team A (hrA)
  private Team teamA;
  private String adminToken; // ACCOUNTS_ADMIN
  private String accountantToken; // ACCOUNTANT of team A
  private String managerToken; // MANAGER of team A (§8a widening)
  private Employee empB; // approved, company B / team B — foreign to team A's accountant + manager
  private Team teamB;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"attendance_sessions\","
            + "\"attendance_breaks\",\"leave_requests\",\"audit_logs\" RESTART IDENTITY CASCADE");
    companyA = company("AAA");
    String companyB = company("BBB");
    User hrA = user(companyA, UserRole.HR, "hra@a.test");
    User accA = user(companyA, UserRole.ACCOUNTANT, "acca@a.test");
    User mgrA = user(companyA, UserRole.MANAGER, "mgra@a.test");
    empA = approved(companyA, hrA.getId(), "AAA-EMP-000001", "Anita Approved");
    teamA = team(companyA, hrA.getId(), accA.getId());
    teamA.setManagerUserId(mgrA.getId()); // the manager of team A (same managerUser mapping as the roster)
    teamA = teams.save(teamA);

    User hrB = user(companyB, UserRole.HR, "hrb@b.test");
    empB = approved(companyB, hrB.getId(), "BBB-EMP-000001", "Bob Approved");
    teamB = team(companyB, hrB.getId(), null);

    adminToken = token(user(null, UserRole.ACCOUNTS_ADMIN, "casey@books.test"), null);
    accountantToken = token(accA, teamA.getId());
    managerToken = token(mgrA, teamA.getId());

    seedJanuaryForEmpA();
  }

  /**
   * Month = 2026-01. Sessions for empA:
   *  S1  Jan 10 19:00 -> Jan 11 03:00 IST (8h) with a 30-min break  -> worked 7.5h = 27000s
   *  S2  Jan 12 19:25 -> Jan 13 03:00 IST (7h35m) LATE, no break     -> worked 27300s, isLate
   *  S3  Feb 01 00:30 -> Feb 01 03:00 IST (2.5h), SHIFT-DAY Jan 31    -> worked 9000s (overnight boundary)
   *  S4  Jan 15 21:00 -> OPEN (not clocked out)                       -> worked 0, but makes clockedInNow true
   * Leaves (approved): CASUAL Jan 20-24 (5d), SICK Dec 30-Jan 02 (clip -> 2d), UNPAID Feb 05-06 (0 in Jan).
   */
  private void seedJanuaryForEmpA() {
    AttendanceSession s1 =
        session(empA, ist(2026, 1, 10, 19, 0), ist(2026, 1, 11, 3, 0), LocalDate.of(2026, 1, 10), false);
    brk(s1, ist(2026, 1, 10, 22, 0), ist(2026, 1, 10, 22, 30)); // 30-min break
    session(empA, ist(2026, 1, 12, 19, 25), ist(2026, 1, 13, 3, 0), LocalDate.of(2026, 1, 12), true);
    // Overnight boundary: clock-in is Feb 1 (calendar) but the shift-day is Jan 31.
    session(empA, ist(2026, 2, 1, 0, 30), ist(2026, 2, 1, 3, 0), LocalDate.of(2026, 1, 31), false);
    // Open session (no clock-out) -> contributes 0 worked, drives clockedInNow.
    openSession(empA, ist(2026, 1, 15, 21, 0), LocalDate.of(2026, 1, 15));

    leave(empA, LeaveType.CASUAL, LocalDate.of(2026, 1, 20), LocalDate.of(2026, 1, 24));
    leave(empA, LeaveType.SICK, LocalDate.of(2025, 12, 30), LocalDate.of(2026, 1, 2));
    leave(empA, LeaveType.UNPAID, LocalDate.of(2026, 2, 5), LocalDate.of(2026, 2, 6));
  }

  @Test
  void employeeSummary_workedExcludesBreaks_overnightInShiftMonth_leavesClipped_adherence_clockedInNow()
      throws Exception {
    JsonNode s = getJson("/accountant/employees/" + empA.getId() + "/attendance/summary?month=2026-01", adminToken);

    // Worked = 27000 + 27300 + 9000 + 0(open) = 63300 (breaks excluded; overnight counted in Jan).
    assertThat(s.get("workedSeconds").asLong()).isEqualTo(63300L);
    assertThat(s.get("breakSeconds").asLong()).isEqualTo(1800L);
    assertThat(s.get("daysPresent").asLong()).isEqualTo(4L); // Jan 10, 12, 15, 31
    assertThat(s.get("lateLogins").asLong()).isEqualTo(1L);
    assertThat(s.get("leavesByType").get("casual").asLong()).isEqualTo(5L);
    assertThat(s.get("leavesByType").get("sick").asLong()).isEqualTo(2L); // Dec 30-Jan 2 clipped to Jan 1-2
    assertThat(s.get("leavesByType").get("unpaid").asLong()).isEqualTo(0L);
    assertThat(s.get("leaveDaysTotal").asLong()).isEqualTo(7L); // all calendar days (unchanged)
    assertThat(s.get("leaveRequests").asLong()).isEqualTo(2L);
    // NEW adherence basis (Mon–Fri): Jan 2026 has 22 working days; 6 fall on approved leave
    // (Jan 1,2,20,21,22,23) -> expected 16; present-on-working = Jan 12,15 (Jan 10/31 are Saturdays) = 2.
    assertThat(s.get("workingDays").asInt()).isEqualTo(22);
    assertThat(s.get("expectedDays").asLong()).isEqualTo(16L);
    assertThat(s.get("adherencePct").asInt()).isEqualTo(13); // round(2/16*100) = 13
    // Unapproved absences: 22 working − 2 present-working − 6 leave-working = 14 (all past).
    assertThat(s.get("unapprovedAbsences").asLong()).isEqualTo(14L);
    // The donut split agrees with the headline worked/break; idle omitted in v1.
    assertThat(s.get("timeComposition").get("workedSeconds").asLong()).isEqualTo(63300L);
    assertThat(s.get("timeComposition").get("breakSeconds").asLong()).isEqualTo(1800L);
    assertThat(s.get("timeComposition").get("idleSeconds").isNull()).isTrue();
    assertThat(s.get("clockedInNow").asBoolean()).isTrue();

    // February: the overnight session (shift-day Jan 31) is NOT here; attendance is zero, unpaid leave shows.
    JsonNode feb = getJson("/accountant/employees/" + empA.getId() + "/attendance/summary?month=2026-02", adminToken);
    assertThat(feb.get("workedSeconds").asLong()).isZero();
    assertThat(feb.get("daysPresent").asLong()).isZero();
    assertThat(feb.get("leavesByType").get("unpaid").asLong()).isEqualTo(2L);
  }

  @Test
  void employeeMonthly_returnsSeriesWithCorrectMonthAndZerosForEmpty() throws Exception {
    JsonNode series = getJson("/accountant/employees/" + empA.getId() + "/attendance/monthly?months=24", adminToken);
    JsonNode months = series.get("months");
    // Last entry is the current shift-month (live).
    assertThat(months.get(months.size() - 1).get("month").asText())
        .isEqualTo(YearMonth.now(ShiftConfig.ZONE).toString());
    JsonNode jan = firstWhere(months, "month", "2026-01");
    assertThat(jan.get("workedSeconds").asLong()).isEqualTo(63300L);
    JsonNode mar = firstWhere(months, "month", "2026-03"); // no data -> zeros, not an error
    assertThat(mar.get("workedSeconds").asLong()).isZero();
    assertThat(mar.get("daysPresent").asLong()).isZero();
  }

  @Test
  void teamSummary_rollsUpAndBatchesTodaySnapshot() throws Exception {
    JsonNode t = getJson("/accountant/teams/" + teamA.getId() + "/attendance/summary?month=2026-01", adminToken);
    assertThat(t.get("employeeCount").asInt()).isEqualTo(1);
    assertThat(t.get("totalLateThisMonth").asLong()).isEqualTo(1L);
    assertThat(t.get("clockedInNow").asLong()).isEqualTo(1L); // the open session
    assertThat(t.get("presentToday").asLong()).isZero(); // seeded sessions are all in January
    assertThat(t.get("onLeaveToday").asLong()).isZero();
    JsonNode row = t.get("employees").get(0);
    assertThat(row.get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(row.get("workedSeconds").asLong()).isEqualTo(63300L);
    assertThat(row.get("lateLogins").asLong()).isEqualTo(1L);
    assertThat(row.get("leaveDaysTotal").asLong()).isEqualTo(7L);
    // Same basis as the employee summary: 22 working − 2 present-working − 6 leave-working = 14.
    assertThat(row.get("unapprovedAbsences").asLong()).isEqualTo(14L);
    assertThat(row.get("clockedInNow").asBoolean()).isTrue();
  }

  @Test
  void adherence_isPresentWorkingOverWorkingMinusLeave_cleanExample() throws Exception {
    // A dedicated April-2026 employee: present on the first 18 of the month's 22 working days,
    // CASUAL leave on Apr 27-28 (2 working days). Expected = 22 − 2 = 20; adherence = 18/20 = 90%.
    Employee e = approved(companyA, empA.getOnboardingHrId(), "AAA-EMP-000042", "Cleo Clean");
    for (LocalDate d : AttendanceCalendar.workingDays(YearMonth.of(2026, 4)).subList(0, 18)) {
      presentDay(e, d);
    }
    leave(e, LeaveType.CASUAL, LocalDate.of(2026, 4, 27), LocalDate.of(2026, 4, 28));

    JsonNode s = getJson("/accountant/employees/" + e.getId() + "/attendance/summary?month=2026-04", adminToken);
    assertThat(s.get("daysPresent").asLong()).isEqualTo(18L);
    assertThat(s.get("workingDays").asInt()).isEqualTo(22);
    assertThat(s.get("expectedDays").asLong()).isEqualTo(20L);
    assertThat(s.get("adherencePct").asInt()).isEqualTo(90); // round(18/20*100)
    // Leftover working days Apr 29-30 are past, unattended, unleaved -> 2 unapproved absences.
    assertThat(s.get("unapprovedAbsences").asLong()).isEqualTo(2L);
  }

  @Test
  void unapprovedAbsences_countPastWorkingDaysOnly_currentMonthExcludesTodayAndFuture() throws Exception {
    // A fresh employee with NO sessions/leaves this (current) shift-month: every past Mon–Fri is an
    // absence; today and every future working day are NEVER counted.
    Employee e = approved(companyA, empA.getOnboardingHrId(), "AAA-EMP-000043", "Nora Now");
    YearMonth now = YearMonth.now(ShiftConfig.ZONE);
    LocalDate today = LocalDate.now(ShiftConfig.ZONE);
    long pastWorkingDays =
        AttendanceCalendar.workingDays(now).stream().filter(d -> d.isBefore(today)).count();

    JsonNode s =
        getJson("/accountant/employees/" + e.getId() + "/attendance/summary?month=" + now, adminToken);
    assertThat(s.get("unapprovedAbsences").asLong()).isEqualTo(pastWorkingDays);
    // Explicitly: the count never reaches into today/future working days.
    assertThat(s.get("unapprovedAbsences").asLong()).isLessThanOrEqualTo(pastWorkingDays);
  }

  @Test
  void adherence_isNull_whenExpectedDaysZero_dividesByZeroSafely() throws Exception {
    // A whole-month approved leave leaves zero expected days -> adherence is N/A (null), never a crash.
    Employee e = approved(companyA, empA.getOnboardingHrId(), "AAA-EMP-000044", "Zeb Zero");
    leave(e, LeaveType.CASUAL, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

    JsonNode s = getJson("/accountant/employees/" + e.getId() + "/attendance/summary?month=2026-02", adminToken);
    assertThat(s.get("expectedDays").asLong()).isZero();
    assertThat(s.get("adherencePct").isNull()).isTrue();
    // Every working day is covered by leave, so there are no unapproved absences either.
    assertThat(s.get("unapprovedAbsences").asLong()).isZero();
  }

  @Test
  void scoping_accountsAdminAny_accountantOwnTeamOnly() throws Exception {
    // ACCOUNTANT: own employee/team -> ok.
    mvc.perform(get("/accountant/employees/" + empA.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isOk());
    mvc.perform(get("/accountant/teams/" + teamA.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isOk());
    // ACCOUNTANT: foreign employee + foreign team -> 404 (never widened).
    mvc.perform(get("/accountant/employees/" + empB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/accountant/teams/" + teamB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + accountantToken))
        .andExpect(status().isNotFound());
    // ACCOUNTS_ADMIN: any company's employee + team -> ok.
    mvc.perform(get("/accountant/employees/" + empB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
    mvc.perform(get("/accountant/teams/" + teamB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  // --- Feature 1: MANAGER own-team analytics (§8a widening) -----------------

  @Test
  void scoping_managerOwnTeamOnly_foreign404_nonAttendanceStays403() throws Exception {
    // MANAGER: own team + own employee (summary + monthly) -> 200, and it's really THEIR team's data.
    JsonNode t = getJson("/accountant/teams/" + teamA.getId() + "/attendance/summary?month=2026-01", managerToken);
    assertThat(t.get("employeeCount").asInt()).isEqualTo(1);
    assertThat(t.get("employees").get(0).get("employeeCode").asText()).isEqualTo("AAA-EMP-000001");
    assertThat(t.get("totalLateThisMonth").asLong()).isEqualTo(1L);
    mvc.perform(get("/accountant/employees/" + empA.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isOk());
    mvc.perform(get("/accountant/employees/" + empA.getId() + "/attendance/monthly")
            .header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isOk());
    // MANAGER: foreign team + foreign employee -> 404 (exactly the accountant rule; never widened).
    mvc.perform(get("/accountant/teams/" + teamB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/accountant/employees/" + empB.getId() + "/attendance/summary")
            .header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isNotFound());
    // MANAGER stays OUT of the rest of the viewer area: only the 3 attendance endpoints are widened.
    mvc.perform(get("/accountant/companies").header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/accountant/employees/" + empA.getId()) // the (record) read
            .header("Authorization", "Bearer " + managerToken))
        .andExpect(status().isForbidden());
  }

  @Test
  void managerMyTeam_resolvesOwnTeamDescriptorForTheUiMount() throws Exception {
    JsonNode t = getJson("/manager/my-team", managerToken);
    assertThat(t.get("teamId").asText()).isEqualTo(teamA.getId());
    assertThat(t.get("companyId").asText()).isEqualTo(companyA);
  }

  // --- Feature 2: custom date range (all three viewer roles) ----------------

  @Test
  void range_equalToAMonth_reproducesTheMonthExactly() throws Exception {
    String base = "/accountant/employees/" + empA.getId() + "/attendance/summary";
    JsonNode m = getJson(base + "?month=2026-01", adminToken);
    JsonNode r = getJson(base + "?from=2026-01-01&to=2026-01-31", adminToken);
    // Every computed metric is identical: a full-month range IS the month.
    for (String f :
        List.of(
            "workedSeconds", "breakSeconds", "daysPresent", "lateLogins", "leaveDaysTotal",
            "leaveRequests", "workingDays", "expectedDays", "unapprovedAbsences", "adherencePct")) {
      assertThat(r.get(f)).as(f).isEqualTo(m.get(f));
    }
    assertThat(r.get("leavesByType")).isEqualTo(m.get("leavesByType"));
    assertThat(r.get("timeComposition")).isEqualTo(m.get("timeComposition"));
    // Month mode carries the YYYY-MM label + the month bounds; range mode has a null label + echoed window.
    assertThat(m.get("month").asText()).isEqualTo("2026-01");
    assertThat(m.get("periodStart").asText()).isEqualTo("2026-01-01");
    assertThat(m.get("periodEnd").asText()).isEqualTo("2026-01-31");
    assertThat(r.get("month").isNull()).isTrue();
    assertThat(r.get("periodStart").asText()).isEqualTo("2026-01-01");
    assertThat(r.get("periodEnd").asText()).isEqualTo("2026-01-31");

    // Same equality at the TEAM endpoint (the row worked matches the month roll-up).
    String teamBase = "/accountant/teams/" + teamA.getId() + "/attendance/summary";
    JsonNode tm = getJson(teamBase + "?month=2026-01", adminToken);
    JsonNode tr = getJson(teamBase + "?from=2026-01-01&to=2026-01-31", adminToken);
    assertThat(tr.get("employees").get(0).get("workedSeconds"))
        .isEqualTo(tm.get("employees").get(0).get("workedSeconds"));
    assertThat(tr.get("employees").get(0).get("unapprovedAbsences"))
        .isEqualTo(tm.get("employees").get(0).get("unapprovedAbsences"));
    assertThat(tr.get("month").isNull()).isTrue();
    assertThat(tr.get("periodStart").asText()).isEqualTo("2026-01-01");
  }

  @Test
  void range_clipsLeaveAndSessionsAtTheBoundary() throws Exception {
    // Window Jan 22–31: the CASUAL Jan 20–24 leave clips to Jan 22–24 (3 days); only the Jan-31 shift-day
    // session falls in range (worked 9000s, one day present); Jan 10/12/15 sessions are outside it.
    JsonNode r =
        getJson(
            "/accountant/employees/" + empA.getId() + "/attendance/summary?from=2026-01-22&to=2026-01-31",
            adminToken);
    assertThat(r.get("leavesByType").get("casual").asLong()).isEqualTo(3L);
    assertThat(r.get("leaveDaysTotal").asLong()).isEqualTo(3L);
    assertThat(r.get("workedSeconds").asLong()).isEqualTo(9000L);
    assertThat(r.get("daysPresent").asLong()).isEqualTo(1L);
  }

  @Test
  void range_workingDaysAreMonToFriWithinAnArbitraryRange() throws Exception {
    // Jan 5 (Mon) → Jan 16 (Fri) 2026 spans two full Mon–Fri weeks = 10 working days.
    JsonNode r =
        getJson(
            "/accountant/employees/" + empA.getId() + "/attendance/summary?from=2026-01-05&to=2026-01-16",
            adminToken);
    assertThat(r.get("workingDays").asInt()).isEqualTo(10);
  }

  @Test
  void range_absencesArePastWorkingDaysOnly_insideTheRange() throws Exception {
    // A fresh employee, current shift-month; a range from the 1st to today counts only PAST Mon–Fri days
    // (today + future never count), matching the month rule but clipped to the range.
    Employee e = approved(companyA, empA.getOnboardingHrId(), "AAA-EMP-000045", "Rhea Range");
    LocalDate today = LocalDate.now(ShiftConfig.ZONE);
    LocalDate first = today.withDayOfMonth(1);
    long pastWorking =
        AttendanceCalendar.workingDays(first, today).stream().filter(d -> d.isBefore(today)).count();
    JsonNode r =
        getJson(
            "/accountant/employees/" + e.getId() + "/attendance/summary?from=" + first + "&to=" + today,
            adminToken);
    assertThat(r.get("unapprovedAbsences").asLong()).isEqualTo(pastWorking);
  }

  @Test
  void range_validation_returns400sForBadInput() throws Exception {
    String base = "/accountant/employees/" + empA.getId() + "/attendance/summary";
    bad(base + "?from=2026-01-31&to=2026-01-01"); // reversed
    bad(base + "?from=2025-01-01&to=2026-06-01"); // span > 366 days
    bad(base + "?month=2026-01&from=2026-01-01&to=2026-01-31"); // month + range together
    bad(base + "?from=2026-01-01"); // only one bound
    bad(base + "?to=2026-01-31"); // only one bound
    bad(base + "?from=2026-01&to=2026-01-31"); // malformed date (YYYY-MM, not YYYY-MM-DD)
  }

  @Test
  void range_worksForAllThreeViewerRolesWithinScope() throws Exception {
    String q = "?from=2026-01-01&to=2026-01-31";
    for (String tok : List.of(adminToken, accountantToken, managerToken)) {
      mvc.perform(get("/accountant/teams/" + teamA.getId() + "/attendance/summary" + q)
              .header("Authorization", "Bearer " + tok))
          .andExpect(status().isOk());
    }
  }

  // --- Period modes (Today = single day) + Team composition aggregate -------

  @Test
  void range_singleDay_returnsThatDaysNumbers_theTodayModeShape() throws Exception {
    // A single-day window (from == to) is the Today-mode call shape. Jan 12 2026 (a Monday) has only
    // empA's LATE session S2 (worked 27300, no break).
    JsonNode d =
        getJson(
            "/accountant/employees/" + empA.getId() + "/attendance/summary?from=2026-01-12&to=2026-01-12",
            adminToken);
    assertThat(d.get("periodStart").asText()).isEqualTo("2026-01-12");
    assertThat(d.get("periodEnd").asText()).isEqualTo("2026-01-12");
    assertThat(d.get("month").isNull()).isTrue();
    assertThat(d.get("workedSeconds").asLong()).isEqualTo(27300L);
    assertThat(d.get("daysPresent").asLong()).isEqualTo(1L);
    assertThat(d.get("lateLogins").asLong()).isEqualTo(1L);
    assertThat(d.get("workingDays").asInt()).isEqualTo(1); // Jan 12 is a working day (Mon)
  }

  @Test
  void teamComposition_aggregatesAreTheSumOfMemberSummaries() throws Exception {
    // Add a SECOND member on team A (onboarded by the same HR) with its own session + break + a 1-day leave.
    Employee empA2 = approved(companyA, empA.getOnboardingHrId(), "AAA-EMP-000050", "Dan Second");
    AttendanceSession s =
        session(empA2, ist(2026, 1, 14, 19, 0), ist(2026, 1, 15, 3, 0), LocalDate.of(2026, 1, 14), false);
    brk(s, ist(2026, 1, 14, 22, 0), ist(2026, 1, 14, 22, 20)); // 20-min break
    leave(empA2, LeaveType.SICK, LocalDate.of(2026, 1, 19), LocalDate.of(2026, 1, 19)); // 1 working day

    JsonNode team = getJson("/accountant/teams/" + teamA.getId() + "/attendance/summary?month=2026-01", adminToken);
    JsonNode m1 = getJson("/accountant/employees/" + empA.getId() + "/attendance/summary?month=2026-01", adminToken);
    JsonNode m2 = getJson("/accountant/employees/" + empA2.getId() + "/attendance/summary?month=2026-01", adminToken);

    assertThat(team.get("employeeCount").asInt()).isEqualTo(2);
    // Every team aggregate is EXACTLY the sum of the two members' computePeriod results.
    assertThat(team.get("teamTimeComposition").get("workedSeconds").asLong())
        .isEqualTo(m1.get("workedSeconds").asLong() + m2.get("workedSeconds").asLong());
    assertThat(team.get("teamTimeComposition").get("breakSeconds").asLong())
        .isEqualTo(m1.get("breakSeconds").asLong() + m2.get("breakSeconds").asLong());
    assertThat(team.get("teamDaysPresent").asLong())
        .isEqualTo(m1.get("daysPresent").asLong() + m2.get("daysPresent").asLong());
    assertThat(team.get("teamLeaveDaysTotal").asLong())
        .isEqualTo(m1.get("leaveDaysTotal").asLong() + m2.get("leaveDaysTotal").asLong());
    assertThat(team.get("teamUnapprovedAbsences").asLong())
        .isEqualTo(m1.get("unapprovedAbsences").asLong() + m2.get("unapprovedAbsences").asLong());
    assertThat(team.get("teamExpectedDays").asLong())
        .isEqualTo(m1.get("expectedDays").asLong() + m2.get("expectedDays").asLong());
    assertThat(team.get("teamLeavesByType").get("sick").asLong())
        .isEqualTo(m1.get("leavesByType").get("sick").asLong() + m2.get("leavesByType").get("sick").asLong());
    assertThat(team.get("teamAdherencePct").isNull()).isFalse(); // Σ expected > 0 here
  }

  @Test
  void teamComposition_presentForAllThreeRoles_crossMonthSpan_echoesWindow() throws Exception {
    // A payroll-cycle-shaped span (26 Jan → 25 Feb) is just a range; the team aggregate is returned for
    // every viewer within scope (ACCOUNTS_ADMIN any, ACCOUNTANT + MANAGER own team — unchanged authz).
    String q = "?from=2026-01-26&to=2026-02-25";
    for (String tok : List.of(adminToken, accountantToken, managerToken)) {
      JsonNode t = getJson("/accountant/teams/" + teamA.getId() + "/attendance/summary" + q, tok);
      assertThat(t.get("periodStart").asText()).isEqualTo("2026-01-26");
      assertThat(t.get("periodEnd").asText()).isEqualTo("2026-02-25");
      assertThat(t.get("month").isNull()).isTrue();
      assertThat(t.has("teamTimeComposition")).isTrue();
      assertThat(t.get("teamTimeComposition").has("workedSeconds")).isTrue();
    }
  }

  // --- helpers --------------------------------------------------------------

  private void bad(String path) throws Exception {
    mvc.perform(get(path).header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isBadRequest());
  }

  private JsonNode getJson(String path, String token) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static JsonNode firstWhere(JsonNode array, String field, String value) {
    for (JsonNode n : array) {
      if (value.equals(n.get(field).asText())) {
        return n;
      }
    }
    throw new AssertionError("no element with " + field + "=" + value);
  }

  private static Instant ist(int y, int mo, int d, int h, int mi) {
    return LocalDateTime.of(LocalDate.of(y, mo, d), LocalTime.of(h, mi)).atZone(ShiftConfig.ZONE).toInstant();
  }

  private AttendanceSession session(
      Employee e, Instant in, Instant out, LocalDate shiftDate, boolean late) {
    AttendanceSession s = new AttendanceSession();
    s.setEmployeeId(e.getId());
    s.setCompanyId(e.getCompanyId());
    s.setClockInAt(in);
    s.setClockOutAt(out);
    s.setShiftDate(shiftDate);
    s.setLate(late);
    return sessions.save(s);
  }

  /** A completed 8h session whose SHIFT-DAY is {@code day} (marks that day present). */
  private void presentDay(Employee e, LocalDate day) {
    Instant in = ist(day.getYear(), day.getMonthValue(), day.getDayOfMonth(), 19, 0);
    session(e, in, in.plusSeconds(28800), day, false);
  }

  private void openSession(Employee e, Instant in, LocalDate shiftDate) {
    AttendanceSession s = new AttendanceSession();
    s.setEmployeeId(e.getId());
    s.setCompanyId(e.getCompanyId());
    s.setClockInAt(in);
    s.setShiftDate(shiftDate);
    s.setLate(false);
    sessions.save(s);
  }

  private void brk(AttendanceSession s, Instant start, Instant end) {
    AttendanceBreak b = new AttendanceBreak();
    b.setSessionId(s.getId());
    b.setCompanyId(s.getCompanyId());
    b.setBreakStartAt(start);
    b.setBreakEndAt(end);
    breaks.save(b);
  }

  private void leave(Employee e, LeaveType type, LocalDate start, LocalDate end) {
    LeaveRequest lr = new LeaveRequest();
    lr.setEmployeeId(e.getId());
    lr.setCompanyId(e.getCompanyId());
    lr.setManagerUserId("mgr-" + e.getId());
    lr.setStartDate(start);
    lr.setEndDate(end);
    lr.setLeaveType(type);
    lr.setReason("seed");
    lr.setStatus(LeaveStatus.APPROVED);
    leaves.save(lr);
  }

  private String company(String code) {
    Company c = new Company();
    c.setName(code + " Inc");
    c.setCode(code);
    c.setMailDomain(code.toLowerCase());
    return companies.save(c).getId();
  }

  private User user(String companyId, UserRole role, String email) {
    User u = new User();
    u.setEmail(email);
    u.setName(email);
    u.setRole(role);
    u.setCompanyId(companyId);
    u.setStatus("ACTIVE");
    return users.save(u);
  }

  private Employee approved(String companyId, String hrId, String code, String name) {
    Employee e = new Employee();
    e.setEmployeeCode(code);
    e.setFullName(name);
    e.setEmail(code.toLowerCase() + "@ext.test");
    e.setDesignation("Engineer");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(EmployeeStatus.APPROVED);
    return employees.save(e);
  }

  private Team team(String companyId, String hrId, String accountantId) {
    Team t = new Team();
    t.setName("Team " + companyId.substring(0, 4));
    t.setCompanyId(companyId);
    t.setHrUserId(hrId);
    t.setAccountantUserId(accountantId);
    return teams.save(t);
  }

  private String token(User u, String teamId) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), u.getRole(), u.getCompanyId(), teamId));
  }
}
