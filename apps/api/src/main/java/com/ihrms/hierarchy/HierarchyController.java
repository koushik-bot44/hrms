package com.ihrms.hierarchy;

import com.ihrms.hierarchy.dto.HierarchyDtos.CompaniesResponse;
import com.ihrms.hierarchy.dto.HierarchyDtos.CompanyBreakdown;
import com.ihrms.hierarchy.dto.HierarchyDtos.PlatformOverview;
import com.ihrms.hierarchy.dto.HierarchyDtos.TeamMembersResponse;
import com.ihrms.hierarchy.dto.HierarchyDtos.TrendsResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform-wide, cross-company, AGGREGATES-ONLY overview reads for the HIERARCHY role (ARCHITECTURE.md
 * §2/§6). READ-ONLY; every response is counts/summaries — no individual employee identity/record. The
 * URL rule ({@code /hierarchy/** -> HIERARCHY}) and this {@code @PreAuthorize} both gate the role.
 */
@RestController
@RequestMapping("/hierarchy")
@PreAuthorize("hasRole('HIERARCHY')")
public class HierarchyController {

  private final HierarchyAnalyticsService analytics;

  public HierarchyController(HierarchyAnalyticsService analytics) {
    this.analytics = analytics;
  }

  /** Platform totals + onboarding funnel + status distribution + ops metrics, in one payload. */
  @GetMapping("/overview")
  public PlatformOverview overview() {
    return analytics.overview();
  }

  /** Monthly trends (last {@code months}, default 12; Asia/Kolkata): joined, approved, offboarded(=0). */
  @GetMapping("/trends")
  public TrendsResponse trends(@RequestParam(defaultValue = "12") int months) {
    return analytics.trends(months);
  }

  /** Employees-per-company (size distribution + drill list): name, archived flag, team + employee counts. */
  @GetMapping("/companies")
  public CompaniesResponse companies() {
    return analytics.companies();
  }

  /** One company's org breakdown: counts + by-status + assigned Company Admin + per-team staff (no PII). */
  @GetMapping("/companies/{companyId}/breakdown")
  public CompanyBreakdown breakdown(@PathVariable String companyId) {
    return analytics.breakdown(companyId);
  }

  /**
   * A team's people (§2 charter widening — bounded): its assigned staff (HR/Manager/Accountant, named) and
   * its employees (name, code, designation, role). Names/codes/designations/roles ONLY — no other PII.
   */
  @GetMapping("/teams/{teamId}/members")
  public TeamMembersResponse teamMembers(@PathVariable String teamId) {
    return analytics.teamMembers(teamId);
  }
}
