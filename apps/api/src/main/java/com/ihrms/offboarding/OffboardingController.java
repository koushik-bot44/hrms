package com.ihrms.offboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.offboarding.dto.OffboardingDtos.CancelRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.InitiateRequest;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingCaseResponse;
import com.ihrms.offboarding.dto.OffboardingDtos.OffboardingCaseView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR-facing offboarding endpoints (§Offboarding stage 1). Initiate/cancel are HR, onboarding-HR-scoped (like
 * verification/approve); the case READ is opened to the record-view viewers (HR/COMPANY_ADMIN/SUPER_ADMIN, the
 * URL rule + the service's canAccessEmployee). Notifications fire post-commit (best-effort).
 */
@RestController
@RequestMapping("/employees/{id}/offboarding")
public class OffboardingController {

  private final OffboardingService offboarding;

  public OffboardingController(OffboardingService offboarding) {
    this.offboarding = offboarding;
  }

  @PostMapping("/initiate")
  @PreAuthorize("hasRole('HR')")
  @ResponseStatus(HttpStatus.CREATED)
  public OffboardingCaseView initiate(
      @PathVariable String id,
      @Valid @RequestBody InitiateRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingCaseView result = offboarding.initiate(actor, id, body, request.getRemoteAddr());
    offboarding.notifyHierarchyAfterInitiate(id); // post-commit, best-effort
    return result;
  }

  /** The case for the record panel — null when the employee has no case. HR/COMPANY_ADMIN/SUPER_ADMIN. */
  @GetMapping
  @PreAuthorize("hasAnyRole('HR','COMPANY_ADMIN','SUPER_ADMIN')")
  public OffboardingCaseResponse get(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return new OffboardingCaseResponse(offboarding.forRecord(actor, id));
  }

  @PostMapping("/cancel")
  @PreAuthorize("hasRole('HR')")
  public OffboardingCaseView cancel(
      @PathVariable String id,
      @Valid @RequestBody(required = false) CancelRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    OffboardingCaseView result =
        offboarding.cancel(actor, id, body == null ? new CancelRequest(null) : body, request.getRemoteAddr());
    // Best-effort: nudge the hierarchy that a request was withdrawn (a cancelled case leaves the pending
    // inbox regardless, so this is a courtesy — the audit records whether it was pending).
    offboarding.notifyHierarchyAfterCancel(id);
    return result;
  }
}
