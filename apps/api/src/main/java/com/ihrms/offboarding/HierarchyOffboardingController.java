package com.ihrms.offboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.offboarding.dto.OffboardingDtos.DecisionRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.HierarchyCaseRow;
import com.ihrms.offboarding.dto.OffboardingDtos.HierarchyPendingRow;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingDecisionResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HIERARCHY role's offboarding approval surface (§Offboarding) — its ONLY write endpoints. HIERARCHY-gated
 * at both layers (the {@code /hierarchy/**} URL rule + this {@code @PreAuthorize}). The pending inbox exposes a
 * deliberately MINIMAL-PII row (charter loosening); nothing else about the employee is reachable. Notifications
 * to the initiating HR fire post-commit (best-effort).
 */
@RestController
@RequestMapping("/hierarchy/offboarding")
@PreAuthorize("hasRole('HIERARCHY')")
public class HierarchyOffboardingController {

  private final OffboardingService offboarding;

  public HierarchyOffboardingController(OffboardingService offboarding) {
    this.offboarding = offboarding;
  }

  /** All companies' PENDING_APPROVAL cases — the minimal-PII inbox. */
  @GetMapping("/pending")
  public List<HierarchyPendingRow> pending() {
    return offboarding.pending();
  }

  /**
   * Every case, newest first, for the Offboarding tab's history and its summary tiles. Read-only.
   *
   * <p>Filters combine with AND and are all optional: {@code companyId}, an INITIATED-date window
   * ({@code from}/{@code to}, inclusive, Asia/Kolkata calendar days) and {@code status}. The row is the
   * inbox's nine minimal-PII fields plus the four outcome fields the charter extension allows.
   */
  @GetMapping("/cases")
  public List<HierarchyCaseRow> cases(
      @RequestParam(required = false) String companyId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) OffboardingStatus status) {
    return offboarding.hierarchyCases(companyId, from, to, status);
  }

  @PostMapping("/{caseId}/approve")
  public OffboardingDecisionResult approve(
      @PathVariable String caseId,
      @Valid @RequestBody(required = false) DecisionRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingDecisionResult result =
        offboarding.approve(
            actor, caseId, body == null ? new DecisionRequest(null) : body, request.getRemoteAddr());
    offboarding.notifyHrAfterDecision(caseId); // post-commit, best-effort
    return result;
  }

  @PostMapping("/{caseId}/reject")
  public OffboardingDecisionResult reject(
      @PathVariable String caseId,
      @Valid @RequestBody DecisionRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingDecisionResult result =
        offboarding.reject(actor, caseId, body, request.getRemoteAddr());
    offboarding.notifyHrAfterDecision(caseId); // post-commit, best-effort
    return result;
  }
}
