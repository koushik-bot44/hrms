package com.ihrms.accountant;

import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeePage;
import com.ihrms.audit.dto.AuditDtos.AuditPage;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.review.dto.ReviewDtos.EmployeeRecordView;
import com.ihrms.review.dto.ReviewDtos.RevealedSensitive;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The READ-ONLY viewer workspace (ARCHITECTURE.md §2/§6), shared by the cross-company ACCOUNTS_ADMIN
 * and the team-scoped ACCOUNTANT — the service scopes each read by the caller's role. APPROVED
 * employees + each one's full record (masked, audited-reveal) + an approval-only audit view. Every
 * handler is a GET/read (the reveal is a read that logs an audit event).
 */
@RestController
@RequestMapping("/accountant")
@PreAuthorize("hasAnyRole('ACCOUNTS_ADMIN', 'ACCOUNTANT')")
public class AccountantController {

  private final AccountantService accountant;

  public AccountantController(AccountantService accountant) {
    this.accountant = accountant;
  }

  @GetMapping("/employees")
  public ApprovedEmployeePage employees(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String companyId,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return accountant.listApproved(actor, search, companyId, pageable);
  }

  @GetMapping("/employees/{id}")
  public EmployeeRecordView record(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return accountant.record(actor, id, request.getRemoteAddr());
  }

  /** Explicit, audited reveal of the masked sensitive values (§6) — the same event HR records. */
  @PostMapping("/employees/{id}/reveal")
  public RevealedSensitive reveal(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return accountant.reveal(actor, id, request.getRemoteAddr());
  }

  @GetMapping("/audit")
  public AuditPage audit(
      @RequestParam(required = false) String companyId,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return accountant.approvalAudit(actor, companyId, from, to, pageable);
  }
}
