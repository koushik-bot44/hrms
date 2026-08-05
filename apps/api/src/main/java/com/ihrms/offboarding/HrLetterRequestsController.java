package com.ihrms.offboarding;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.offboarding.dto.OffboardingDocDtos.HrLetterRequestsResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The HR's letter-requests inbox (§3.6) — the HR side of the offboarding-letters requests machinery, the
 * mirror of the Accountant's {@code /requests/team} queue. Lists every Relieving/Experience letter request
 * ROUTED to this HR (the case/onboarding-HR scope) + the pending count for the nav badge. HR-only; the service
 * scopes to the acting HR's own id (a foreign HR sees nothing). Sits under {@code /requests/**} (authenticated)
 * with the HR role gate here; the Accountant's {@code /requests/team/**} inbox is untouched.
 */
@RestController
@RequestMapping("/requests/hr-letters")
@PreAuthorize("hasRole('HR')")
public class HrLetterRequestsController {

  private final OffboardingLetterService letters;

  public HrLetterRequestsController(OffboardingLetterService letters) {
    this.letters = letters;
  }

  @GetMapping
  public HrLetterRequestsResponse list(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return letters.hrLetterRequests(actor);
  }
}
