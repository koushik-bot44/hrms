package com.ihrms.onboarding;

import com.ihrms.auth.AuthorizationService;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.onboarding.dto.InviteDtos.InviteContext;
import com.ihrms.onboarding.dto.InviteDtos.ValidateInviteRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The PUBLIC, unauthenticated invite-validation endpoint (§3.2/§6). The onboarding door posts the opaque token
 * from the emailed link; a valid, active invite → 200 with the door's minimal context (prefill email + company
 * display info). Anything else — unknown / expired / revoked / past-onboarding / archived company — is a SINGLE
 * generic {@code 410 Gone} (never distinguishing the reason: anti-enumeration, §6). It is permitAll in
 * SecurityConfig and rate-limited by {@code RateLimitFilter} ({@code /public/onboarding/**}). The token in the
 * body is never logged, and this is a pure read — the token is not consumed here (that happens on OTP verify).
 */
@RestController
public class PublicInviteController {

  private final InviteTokenService inviteTokens;
  private final CompanyRepository companies;
  private final AuthorizationService authz;

  public PublicInviteController(
      InviteTokenService inviteTokens, CompanyRepository companies, AuthorizationService authz) {
    this.inviteTokens = inviteTokens;
    this.companies = companies;
    this.authz = authz;
  }

  @PostMapping("/public/onboarding/invite/validate")
  public InviteContext validate(@Valid @RequestBody ValidateInviteRequest body) {
    Employee employee =
        inviteTokens
            .resolve(body.token())
            .map(InviteTokenService.ResolvedInvite::employee)
            .filter(e -> !authz.isCompanyDeleted(e.getCompanyId()))
            .orElseThrow(this::gone);
    Company company =
        companies.findById(employee.getCompanyId()).filter(c -> c.getDeletedAt() == null).orElseThrow(this::gone);
    return new InviteContext(employee.getEmail(), company.getSlug(), company.getName());
  }

  /** One generic terminal state for every invalid-token reason — no expired-vs-unknown distinction (§6). */
  private ResponseStatusException gone() {
    return new ResponseStatusException(HttpStatus.GONE, "This invite link is invalid or has expired");
  }
}
