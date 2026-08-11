package com.ihrms.onboarding.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Public invite-validation DTOs (§3.2/§6). The onboarding door posts the opaque {@code token} from the emailed
 * link; on success the response carries only the minimal context the door needs — the candidate's prefill email
 * and the company display info (already public via {@code /public/companies/{slug}}). Nothing else is exposed.
 */
public final class InviteDtos {

  private InviteDtos() {}

  /** Body of {@code POST /public/onboarding/invite/validate}. */
  public record ValidateInviteRequest(
      @NotBlank(message = "An invite token is required") String token) {}

  /** 200 context for a valid, active invite — email prefill + company display info. */
  public record InviteContext(String email, String companySlug, String companyName) {}
}
