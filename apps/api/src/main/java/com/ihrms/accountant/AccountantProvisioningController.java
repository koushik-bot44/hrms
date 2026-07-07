package com.ihrms.accountant;

import com.ihrms.accountant.dto.AccountantDtos.AccountantStatus;
import com.ihrms.accountant.dto.AccountantDtos.ProvisionAccountantRequest;
import com.ihrms.accountant.dto.AccountantDtos.ProvisionAccountantResult;
import com.ihrms.auth.IhrmsPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** SUPER_ADMIN provisions the single Accounts Admin (ARCHITECTURE.md §2). Read status + create only. */
@RestController
@RequestMapping("/provisioning/accounts-admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AccountantProvisioningController {

  private final AccountantService accountant;

  public AccountantProvisioningController(AccountantService accountant) {
    this.accountant = accountant;
  }

  @GetMapping
  public AccountantStatus status() {
    return accountant.status();
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public ProvisionAccountantResult provision(
      @Valid @RequestBody ProvisionAccountantRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return accountant.provision(actor, body, request.getRemoteAddr());
  }
}
