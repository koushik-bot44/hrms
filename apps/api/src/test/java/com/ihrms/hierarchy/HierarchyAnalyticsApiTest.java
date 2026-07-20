package com.ihrms.hierarchy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.attendance.ShiftConfig;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.ApprovalRequest;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HIERARCHY aggregation reads (ARCHITECTURE.md §2/§6): cross-company, aggregates-only overview,
 * trends, companies + per-company org breakdown. Seeds multiple companies (incl. an archived one), teams
 * with assigned staff, and employees across ALL statuses over several months (some approved with a
 * decision time, some stuck > 7 days). Asserts totals/funnel/ops/trends/companies/breakdown, that NO
 * employee PII leaks (breakdown names staff only), and role gating (HIERARCHY ok / others 403).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class HierarchyAnalyticsApiTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TokenService tokens;
  @Autowired CompanyRepository companies;
  @Autowired UserRepository users;
  @Autowired TeamRepository teams;
  @Autowired EmployeeRepository employees;
  @Autowired ApprovalRequestRepository approvals;
  @Autowired JdbcTemplate jdbc;

  private static final String PII = "PIISECRET"; // marker in every employee name — must never surface

  private String hierToken;
  private String superToken;
  private String companyA;
  private String companyB;
  private String companyZ; // archived
  private User hrA;
  private User hrB;
  private User mgrA;
  private User accA;
  private Team teamA;

  private YearMonth oldMonth;
  private YearMonth thisMonth;

  @BeforeEach
  void setup() {
    jdbc.execute(
        "TRUNCATE \"users\",\"employees\",\"companies\",\"teams\",\"approval_requests\","
            + "\"notifications\",\"audit_logs\" RESTART IDENTITY CASCADE");

    companyA = company("Acme", "AAA", false);
    companyB = company("Beta", "BBB", false);
    companyZ = company("Zeta", "ZZZ", true); // archived

    user(companyA, UserRole.COMPANY_ADMIN, "ca@a.test");
    hrA = user(companyA, UserRole.HR, "hra@a.test");
    mgrA = user(companyA, UserRole.MANAGER, "mgra@a.test");
    accA = user(companyA, UserRole.ACCOUNTANT, "acca@a.test");
    hrB = user(companyB, UserRole.HR, "hrb@b.test");

    teamA = team(companyA, "Team A", hrA.getId(), mgrA.getId(), accA.getId());
    team(companyB, "Team B", hrB.getId(), null, null); // no manager/accountant (null-slot test)

    // Two IST months: "old" (3 months ago, all >7d -> stuck) and "this" month (recent).
    Instant old = LocalDate.now(ShiftConfig.ZONE).minusMonths(3).withDayOfMonth(15)
        .atTime(12, 0).atZone(ShiftConfig.ZONE).toInstant();
    Instant recent = Instant.now();
    oldMonth = YearMonth.from(old.atZone(ShiftConfig.ZONE));
    thisMonth = YearMonth.now(ShiftConfig.ZONE);

    // companyA / hrA (7 employees): 2 INVITED, IN_PROGRESS, SUBMITTED, 3 APPROVED.
    employee(companyA, hrA.getId(), EmployeeStatus.INVITED, old); // stuck
    employee(companyA, hrA.getId(), EmployeeStatus.INVITED, recent); // NOT stuck (recent)
    employee(companyA, hrA.getId(), EmployeeStatus.IN_PROGRESS, old); // stuck
    employee(companyA, hrA.getId(), EmployeeStatus.SUBMITTED, old); // stuck
    Employee ap1 = employee(companyA, hrA.getId(), EmployeeStatus.APPROVED, old);
    Employee ap2 = employee(companyA, hrA.getId(), EmployeeStatus.APPROVED, old);
    Employee ap3 = employee(companyA, hrA.getId(), EmployeeStatus.APPROVED, old);

    // companyB / hrB (3 employees): REVISION_REQUESTED, HR_VERIFIED, REJECTED.
    employee(companyB, hrB.getId(), EmployeeStatus.REVISION_REQUESTED, old); // stuck
    employee(companyB, hrB.getId(), EmployeeStatus.HR_VERIFIED, old); // stuck
    employee(companyB, hrB.getId(), EmployeeStatus.REJECTED, old); // terminal — NOT stuck

    // Approvals for the 3 approved (companyA/teamA): decidedAt = createdAt + 4/5/6 days -> avg 5 days.
    approval(ap1, hrA.getId(), mgrA.getId(), teamA.getId(), old.plus(4, ChronoUnit.DAYS));
    approval(ap2, hrA.getId(), mgrA.getId(), teamA.getId(), old.plus(5, ChronoUnit.DAYS));
    approval(ap3, hrA.getId(), mgrA.getId(), teamA.getId(), old.plus(6, ChronoUnit.DAYS));

    hierToken = token(user(null, UserRole.HIERARCHY, "hank@ihrms"), UserRole.HIERARCHY);
    superToken = token(user(null, UserRole.SUPER_ADMIN, "super@x.test"), UserRole.SUPER_ADMIN);
  }

  @Test
  void overviewReturnsTotalsFunnelAndOpsMetrics() throws Exception {
    JsonNode o = getJson("/hierarchy/overview");

    // Totals.
    JsonNode t = o.get("totals");
    assertThat(t.get("companies").get("total").asLong()).isEqualTo(3);
    assertThat(t.get("companies").get("active").asLong()).isEqualTo(2);
    assertThat(t.get("companies").get("archived").asLong()).isEqualTo(1);
    assertThat(t.get("teams").asLong()).isEqualTo(2);
    assertThat(t.get("employees").asLong()).isEqualTo(10);
    JsonNode staff = t.get("staff");
    assertThat(staff.get("companyAdmins").asLong()).isEqualTo(1);
    assertThat(staff.get("hrs").asLong()).isEqualTo(2);
    assertThat(staff.get("managers").asLong()).isEqualTo(1);
    assertThat(staff.get("accountants").asLong()).isEqualTo(1);
    assertThat(staff.get("accountsAdmins").asLong()).isZero();

    // Funnel (= statusDistribution).
    JsonNode f = o.get("funnel");
    assertThat(f.get("invited").asLong()).isEqualTo(2);
    assertThat(f.get("inProgress").asLong()).isEqualTo(1);
    assertThat(f.get("submitted").asLong()).isEqualTo(1);
    assertThat(f.get("revisionRequested").asLong()).isEqualTo(1);
    assertThat(f.get("hrVerified").asLong()).isEqualTo(1);
    assertThat(f.get("approved").asLong()).isEqualTo(3);
    assertThat(f.get("rejected").asLong()).isEqualTo(1);
    assertThat(o.get("statusDistribution")).isEqualTo(f);

    // Ops.
    JsonNode ops = o.get("ops");
    assertThat(ops.get("totalOnboarded").asLong()).isEqualTo(10);
    assertThat(ops.get("approved").asLong()).isEqualTo(3);
    assertThat(ops.get("onboardingCompletionRate").asDouble()).isEqualTo(0.3); // 3/10
    assertThat(ops.get("averageTimeToApprovalDays").asDouble()).isEqualTo(5.0); // mean(4,5,6)
    assertThat(ops.get("stuckThresholdDays").asInt())
        .isEqualTo(HierarchyAnalyticsService.STUCK_THRESHOLD_DAYS);
    // Stuck = pre-approval AND older than 7d: 5 (all old pre-approval; the recent INVITED + REJECTED excluded).
    assertThat(ops.get("stuckOnboardings").asLong()).isEqualTo(5);
    long byStageSum = 0;
    for (JsonNode s : ops.get("stuckByStage")) {
      byStageSum += s.get("count").asLong();
      assertThat(s.get("status").asText()).isNotEqualTo("REJECTED").isNotEqualTo("APPROVED");
    }
    assertThat(byStageSum).isEqualTo(5);

    // No employee PII in the overview.
    assertThat(o.toString()).doesNotContain(PII);
  }

  @Test
  void trendsBucketJoinedAndApprovedByIstMonthWithZeroFillAndOffboardedPlaceholder() throws Exception {
    JsonNode r = getJson("/hierarchy/trends?months=12");
    assertThat(r.get("months").asInt()).isEqualTo(12);
    JsonNode series = r.get("series");
    assertThat(series).hasSize(12);

    JsonNode oldPt = monthPoint(series, oldMonth.toString());
    assertThat(oldPt.get("joined").asLong()).isEqualTo(9); // 9 seeded at the old month
    assertThat(oldPt.get("approved").asLong()).isEqualTo(3); // 3 approved decided that month
    assertThat(oldPt.get("offboarded").asLong()).isZero();

    JsonNode nowPt = monthPoint(series, thisMonth.toString());
    assertThat(nowPt.get("joined").asLong()).isEqualTo(1); // the recent INVITED
    assertThat(nowPt.get("approved").asLong()).isZero();

    // A month with no seeded activity is zero, not missing/error.
    JsonNode emptyPt = monthPoint(series, thisMonth.minusMonths(1).toString());
    assertThat(emptyPt.get("joined").asLong()).isZero();
    assertThat(emptyPt.get("approved").asLong()).isZero();

    // Series is oldest -> newest.
    assertThat(series.get(0).get("month").asText())
        .isEqualTo(thisMonth.minusMonths(11).toString());
    assertThat(series.get(11).get("month").asText()).isEqualTo(thisMonth.toString());

    // Every offboarded is the 0 placeholder.
    for (JsonNode p : series) {
      assertThat(p.get("offboarded").asLong()).isZero();
    }
  }

  @Test
  void companiesListsEmployeeCountsAndFlagsTheArchivedOne() throws Exception {
    JsonNode rows = getJson("/hierarchy/companies").get("companies");
    JsonNode a = companyRow(rows, companyA);
    assertThat(a.get("archived").asBoolean()).isFalse();
    assertThat(a.get("teamCount").asLong()).isEqualTo(1);
    assertThat(a.get("employeeCount").asLong()).isEqualTo(7);
    JsonNode b = companyRow(rows, companyB);
    assertThat(b.get("employeeCount").asLong()).isEqualTo(3);
    JsonNode z = companyRow(rows, companyZ);
    assertThat(z.get("archived").asBoolean()).isTrue();
    assertThat(z.get("employeeCount").asLong()).isZero();
    assertThat(z.get("teamCount").asLong()).isZero();
  }

  @Test
  void breakdownNamesAssignedStaffAndTeamCountsButNoEmployeePii() throws Exception {
    JsonNode a = getJson("/hierarchy/companies/" + companyA + "/breakdown");
    assertThat(a.get("employeeCount").asLong()).isEqualTo(7);
    assertThat(a.get("teamCount").asLong()).isEqualTo(1);
    assertThat(a.get("byStatus").get("approved").asLong()).isEqualTo(3);
    assertThat(a.get("byStatus").get("invited").asLong()).isEqualTo(2);
    assertThat(a.get("companyAdmin").get("email").asText()).isEqualTo("ca@a.test");
    JsonNode teamRow = a.get("teams").get(0);
    assertThat(teamRow.get("name").asText()).isEqualTo("Team A");
    assertThat(teamRow.get("hr").get("email").asText()).isEqualTo("hra@a.test");
    assertThat(teamRow.get("manager").get("email").asText()).isEqualTo("mgra@a.test");
    assertThat(teamRow.get("accountant").get("email").asText()).isEqualTo("acca@a.test");
    assertThat(teamRow.get("employeeCount").asLong()).isEqualTo(7); // all onboarded by hrA
    // Names STAFF only — no employee identity anywhere in the payload.
    assertThat(a.toString()).doesNotContain(PII);

    // companyB: unassigned manager/accountant slots -> absent; no COMPANY_ADMIN -> companyAdmin absent.
    JsonNode b = getJson("/hierarchy/companies/" + companyB + "/breakdown");
    assertThat(b.get("employeeCount").asLong()).isEqualTo(3);
    assertThat(b.has("companyAdmin")).isFalse(); // NON_NULL — absent when unassigned
    JsonNode bt = b.get("teams").get(0);
    assertThat(bt.get("hr").get("email").asText()).isEqualTo("hrb@b.test");
    assertThat(bt.has("manager")).isFalse();
    assertThat(bt.has("accountant")).isFalse();
    assertThat(b.toString()).doesNotContain(PII);
  }

  @Test
  void aggregateEndpointsAreHierarchyOnly() throws Exception {
    // A non-HIERARCHY role (SUPER_ADMIN) is refused on every aggregate endpoint.
    for (String path :
        new String[] {
          "/hierarchy/overview", "/hierarchy/trends", "/hierarchy/companies",
          "/hierarchy/companies/" + companyA + "/breakdown"
        }) {
      mvc.perform(get(path).header("Authorization", "Bearer " + superToken))
          .andExpect(status().isForbidden());
      mvc.perform(get(path).header("Authorization", "Bearer " + hierToken)).andExpect(status().isOk());
    }
  }

  // --- helpers --------------------------------------------------------------

  private JsonNode getJson(String path) throws Exception {
    return json.readTree(
        mvc.perform(get(path).header("Authorization", "Bearer " + hierToken))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString());
  }

  private static JsonNode monthPoint(JsonNode series, String month) {
    for (JsonNode p : series) {
      if (month.equals(p.get("month").asText())) {
        return p;
      }
    }
    throw new AssertionError("no trend point for " + month);
  }

  private static JsonNode companyRow(JsonNode rows, String id) {
    for (JsonNode r : rows) {
      if (id.equals(r.get("id").asText())) {
        return r;
      }
    }
    throw new AssertionError("no company row for " + id);
  }

  private String company(String name, String code, boolean archived) {
    Company c = new Company();
    c.setName(name);
    c.setCode(code);
    c.setMailDomain(code.toLowerCase());
    if (archived) {
      c.setStatus("DELETED");
      c.setDeletedAt(Instant.now());
    }
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

  private Team team(String companyId, String name, String hrId, String mgrId, String accId) {
    Team t = new Team();
    t.setCompanyId(companyId);
    t.setName(name);
    t.setHrUserId(hrId);
    t.setManagerUserId(mgrId);
    t.setAccountantUserId(accId);
    return teams.save(t);
  }

  private Employee employee(String companyId, String hrId, EmployeeStatus status, Instant createdAt) {
    Employee e = new Employee();
    e.setFullName(PII + " Employee");
    e.setEmail(PII.toLowerCase() + "-" + java.util.UUID.randomUUID() + "@x.test");
    e.setCompanyId(companyId);
    e.setOnboardingHrId(hrId);
    e.setStatus(status);
    if (status == EmployeeStatus.APPROVED) {
      e.setEmployeeCode("EMP-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }
    e = employees.save(e);
    // createdAt is @CreationTimestamp (not entity-settable) — set the controlled value directly (UTC).
    jdbc.update(
        "UPDATE \"employees\" SET \"createdAt\" = ? WHERE \"id\" = ?",
        LocalDateTime.ofInstant(createdAt, ZoneOffset.UTC),
        e.getId());
    return e;
  }

  private void approval(Employee e, String hrId, String mgrId, String teamId, Instant decidedAt) {
    ApprovalRequest a = new ApprovalRequest();
    a.setEmployeeId(e.getId());
    a.setHrUserId(hrId);
    a.setManagerUserId(mgrId);
    a.setTeamId(teamId);
    a.setStatus(ApprovalStatus.APPROVED);
    a.setDecidedAt(decidedAt);
    approvals.save(a);
  }

  private String token(User u, UserRole role) {
    return tokens.issueAccess(
        new IhrmsPrincipal.User(u.getId(), u.getEmail(), u.getName(), role, u.getCompanyId(), null));
  }
}
