package com.ihrms.dashboard;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.dashboard.dto.DashboardDtos.DashboardSummary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role dashboard (§2/§7/§9). Any authenticated principal gets the summary for THEIR role, scoped by
 * the same rules as their list views. Read-only (a GET → not audited).
 */
@RestController
@RequestMapping("/dashboard")
public class DashboardController {

  private final DashboardService dashboard;

  public DashboardController(DashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping("/summary")
  public DashboardSummary summary(@AuthenticationPrincipal IhrmsPrincipal principal) {
    return dashboard.summary(principal);
  }
}
