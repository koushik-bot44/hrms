package com.ihrms.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Role dashboard summary (§2/§7/§9). One endpoint returns the summary for the current principal's
 * role: a list of scoped stat cards + a scoped recent-activity feed (+ onboarding progress for an
 * employee). springdoc-visible so /v3/api-docs reflects it and the web types regenerate.
 */
public final class DashboardDtos {

  private DashboardDtos() {}

  /** One stat card: a scoped COUNT. The web maps {@code key} → the filtered list route for drill-down. */
  public record StatCard(String key, String label, long value) {}

  /** A recent-activity line, scoped to the viewer. {@code company} is set only for the Super Admin. */
  public record ActivityItem(
      String at,
      String actorName,
      String action,
      String subjectName,
      String type,
      @JsonInclude(JsonInclude.Include.NON_NULL) String company) {}

  /** Employee onboarding progress (only present for the EMPLOYEE role). */
  public record EmployeeProgress(
      int formsCompleted,
      int formsTotal,
      String status,
      @JsonInclude(JsonInclude.Include.NON_NULL) String employeeId,
      String nextAction) {}

  public record DashboardSummary(
      String role,
      List<StatCard> stats,
      List<ActivityItem> recentActivity,
      @JsonInclude(JsonInclude.Include.NON_NULL) EmployeeProgress employeeProgress) {}
}
