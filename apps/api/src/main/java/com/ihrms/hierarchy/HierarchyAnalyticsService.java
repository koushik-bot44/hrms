package com.ihrms.hierarchy;

import com.ihrms.attendance.ShiftConfig;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Team;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.ApprovalRequestRepository;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.TeamRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.hierarchy.dto.HierarchyDtos.CompaniesResponse;
import com.ihrms.hierarchy.dto.HierarchyDtos.CompanyBreakdown;
import com.ihrms.hierarchy.dto.HierarchyDtos.CompanySizeRow;
import com.ihrms.hierarchy.dto.HierarchyDtos.CompanyTotals;
import com.ihrms.hierarchy.dto.HierarchyDtos.OnboardingFunnel;
import com.ihrms.hierarchy.dto.HierarchyDtos.OpsMetrics;
import com.ihrms.hierarchy.dto.HierarchyDtos.PlatformOverview;
import com.ihrms.hierarchy.dto.HierarchyDtos.PlatformTotals;
import com.ihrms.hierarchy.dto.HierarchyDtos.StaffRef;
import com.ihrms.hierarchy.dto.HierarchyDtos.StaffTotals;
import com.ihrms.hierarchy.dto.HierarchyDtos.StuckStage;
import com.ihrms.hierarchy.dto.HierarchyDtos.TeamBreakdown;
import com.ihrms.hierarchy.dto.HierarchyDtos.TrendPoint;
import com.ihrms.hierarchy.dto.HierarchyDtos.TrendsResponse;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-wide, cross-company, AGGREGATES-ONLY overview reads for the HIERARCHY role (ARCHITECTURE.md
 * §2/§6). Every figure is a GROUP BY / COUNT — a constant number of queries per endpoint (no N+1). No
 * response carries an individual employee's identity or record; the per-company breakdown names STAFF
 * (admins/HR/Manager/Accountant), which is org data, not employee PII. Read-only; nothing here mutates.
 */
@Service
public class HierarchyAnalyticsService {

  /** A pre-approval onboarding older than this (by createdAt) counts as "stuck" (§2). */
  public static final int STUCK_THRESHOLD_DAYS = 7;

  private static final String ARCHIVED = "DELETED"; // company.status value for archived
  private static final int MAX_TREND_MONTHS = 60;

  /** The pre-approval states an employee can be "stuck" in (REJECTED is terminal, not stuck). */
  private static final Set<EmployeeStatus> PRE_APPROVAL =
      Set.of(
          EmployeeStatus.INVITED,
          EmployeeStatus.IN_PROGRESS,
          EmployeeStatus.SUBMITTED,
          EmployeeStatus.HR_VERIFIED,
          EmployeeStatus.REVISION_REQUESTED);

  private final EmployeeRepository employees;
  private final ApprovalRequestRepository approvals;
  private final CompanyRepository companies;
  private final TeamRepository teams;
  private final UserRepository users;

  public HierarchyAnalyticsService(
      EmployeeRepository employees,
      ApprovalRequestRepository approvals,
      CompanyRepository companies,
      TeamRepository teams,
      UserRepository users) {
    this.employees = employees;
    this.approvals = approvals;
    this.companies = companies;
    this.teams = teams;
    this.users = users;
  }

  // --- GET /hierarchy/overview ----------------------------------------------

  @Transactional(readOnly = true)
  public PlatformOverview overview() {
    OnboardingFunnel funnel = funnel(employees.countGroupByStatus());

    long totalCompanies = companies.count();
    long archived = companies.countByStatus(ARCHIVED);
    CompanyTotals companyTotals =
        new CompanyTotals(totalCompanies, totalCompanies - archived, archived);
    PlatformTotals totals =
        new PlatformTotals(companyTotals, teams.count(), employees.count(), staffTotals());

    long totalOnboarded = totals.employees();
    long approved = funnel.approved();
    double completionRate = totalOnboarded == 0 ? 0.0 : round(approved / (double) totalOnboarded, 4);
    Double avgDays = averageTimeToApprovalDays();

    Instant threshold = Instant.now().minus(STUCK_THRESHOLD_DAYS, ChronoUnit.DAYS);
    List<StuckStage> stuckByStage = new ArrayList<>();
    long stuck = 0;
    for (Object[] row : employees.countStuckByStage(PRE_APPROVAL, threshold)) {
      long c = asLong(row[1]);
      stuckByStage.add(new StuckStage((EmployeeStatus) row[0], c));
      stuck += c;
    }

    OpsMetrics ops =
        new OpsMetrics(
            totalOnboarded, approved, completionRate, avgDays, STUCK_THRESHOLD_DAYS, stuck, stuckByStage);
    return new PlatformOverview(totals, funnel, funnel, ops);
  }

  // --- GET /hierarchy/trends?months=N ---------------------------------------

  @Transactional(readOnly = true)
  public TrendsResponse trends(int months) {
    int n = Math.max(1, Math.min(months, MAX_TREND_MONTHS));
    Map<String, Long> joined = monthMap(employees.joinedPerIstMonth());
    Map<String, Long> approved = monthMap(approvals.approvedPerIstMonth());

    YearMonth end = YearMonth.now(ShiftConfig.ZONE);
    List<TrendPoint> series = new ArrayList<>(n);
    for (int i = n - 1; i >= 0; i--) {
      String key = end.minusMonths(i).toString(); // "YYYY-MM" (Asia/Kolkata)
      // offboarded is a 0 placeholder — offboarding isn't built yet (§2).
      series.add(new TrendPoint(key, joined.getOrDefault(key, 0L), approved.getOrDefault(key, 0L), 0L));
    }
    return new TrendsResponse(n, series);
  }

  // --- GET /hierarchy/companies ---------------------------------------------

  @Transactional(readOnly = true)
  public CompaniesResponse companies() {
    Map<String, Long> teamCounts = countMap(teams.countGroupByCompany());
    Map<String, Long> empCounts = countMap(employees.countGroupByCompany());
    List<CompanySizeRow> rows = new ArrayList<>();
    for (Company c : companies.findAllByOrderByCreatedAtDesc()) {
      rows.add(
          new CompanySizeRow(
              c.getId(),
              c.getName(),
              c.getCode(),
              ARCHIVED.equals(c.getStatus()),
              teamCounts.getOrDefault(c.getId(), 0L),
              empCounts.getOrDefault(c.getId(), 0L)));
    }
    return new CompaniesResponse(rows);
  }

  // --- GET /hierarchy/companies/{id}/breakdown ------------------------------

  @Transactional(readOnly = true)
  public CompanyBreakdown breakdown(String companyId) {
    Company company =
        companies
            .findById(companyId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Company not found"));

    // Resolve staff names/emails from the company's own users (org data — never employee PII).
    Map<String, User> staff = new HashMap<>();
    User companyAdmin = null;
    for (User u : users.findByCompanyId(companyId)) {
      staff.put(u.getId(), u);
      if (companyAdmin == null && u.getRole() == UserRole.COMPANY_ADMIN) {
        companyAdmin = u;
      }
    }

    Map<String, Long> perHr = countMap(employees.countByCompanyGroupByHr(companyId));
    List<TeamBreakdown> teamRows = new ArrayList<>();
    for (Team t : teams.findByCompanyIdOrderByCreatedAtDesc(companyId)) {
      teamRows.add(
          new TeamBreakdown(
              t.getId(),
              t.getName(),
              staffRef(staff, t.getHrUserId()),
              staffRef(staff, t.getManagerUserId()),
              staffRef(staff, t.getAccountantUserId()),
              t.getHrUserId() == null ? 0L : perHr.getOrDefault(t.getHrUserId(), 0L)));
    }

    return new CompanyBreakdown(
        company.getId(),
        company.getName(),
        ARCHIVED.equals(company.getStatus()),
        teamRows.size(),
        employees.countByCompanyId(companyId),
        funnel(employees.countByCompanyGroupByStatus(companyId)),
        staffRef(companyAdmin),
        teamRows);
  }

  // --- helpers --------------------------------------------------------------

  private StaffTotals staffTotals() {
    Map<UserRole, Long> m = new EnumMap<>(UserRole.class);
    for (Object[] row : users.countGroupByRole()) {
      m.put((UserRole) row[0], asLong(row[1]));
    }
    return new StaffTotals(
        m.getOrDefault(UserRole.COMPANY_ADMIN, 0L),
        m.getOrDefault(UserRole.HR, 0L),
        m.getOrDefault(UserRole.MANAGER, 0L),
        m.getOrDefault(UserRole.ACCOUNTANT, 0L),
        m.getOrDefault(UserRole.ACCOUNTS_ADMIN, 0L));
  }

  private static OnboardingFunnel funnel(List<Object[]> rows) {
    Map<EmployeeStatus, Long> m = new EnumMap<>(EmployeeStatus.class);
    for (Object[] row : rows) {
      m.put((EmployeeStatus) row[0], asLong(row[1]));
    }
    return new OnboardingFunnel(
        m.getOrDefault(EmployeeStatus.INVITED, 0L),
        m.getOrDefault(EmployeeStatus.IN_PROGRESS, 0L),
        m.getOrDefault(EmployeeStatus.SUBMITTED, 0L),
        m.getOrDefault(EmployeeStatus.REVISION_REQUESTED, 0L),
        m.getOrDefault(EmployeeStatus.HR_VERIFIED, 0L),
        m.getOrDefault(EmployeeStatus.APPROVED, 0L),
        m.getOrDefault(EmployeeStatus.REJECTED, 0L));
  }

  /** Mean days between onboard (Employee.createdAt) and approval (decidedAt); null when none approved. */
  private Double averageTimeToApprovalDays() {
    Double seconds = approvals.avgSecondsToApproval();
    return seconds == null ? null : round(seconds / 86_400.0, 2);
  }

  private static StaffRef staffRef(Map<String, User> staff, String userId) {
    if (userId == null) {
      return null;
    }
    return staffRef(staff.get(userId));
  }

  private static StaffRef staffRef(User u) {
    return u == null ? null : new StaffRef(u.getName(), u.getEmail());
  }

  /** {@code [String key, count]} rows -> map (key may be a companyId or a "YYYY-MM"). */
  private static Map<String, Long> countMap(List<Object[]> rows) {
    Map<String, Long> m = new HashMap<>();
    for (Object[] row : rows) {
      m.put((String) row[0], asLong(row[1]));
    }
    return m;
  }

  private static Map<String, Long> monthMap(List<Object[]> rows) {
    return countMap(rows);
  }

  /** COUNT is a JPQL {@code Long} or a native {@code BigInteger}/{@code Long} — normalize. */
  private static long asLong(Object value) {
    return ((Number) value).longValue();
  }

  private static double round(double value, int decimals) {
    double factor = Math.pow(10, decimals);
    return Math.round(value * factor) / factor;
  }
}
