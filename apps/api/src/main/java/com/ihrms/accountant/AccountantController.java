package com.ihrms.accountant;

import com.ihrms.accountant.dto.AccountantDtos.ApprovedEmployeePage;
import com.ihrms.audit.AuditQueryService;
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
 * The Accountant's READ-ONLY, cross-company workspace (ARCHITECTURE.md §2/§6): APPROVED employees
 * across all companies + each one's full record (masked, audited-reveal) + an approval-only audit view.
 * ACCOUNTANT-gated; every handler is a GET/read (the reveal is a read that logs an audit event).
 */
@RestController
@RequestMapping("/accountant")
@PreAuthorize("hasRole('ACCOUNTANT')")
public class AccountantController {

  private final AccountantService accountant;
  private final AuditQueryService auditQuery;

  public AccountantController(AccountantService accountant, AuditQueryService auditQuery) {
    this.accountant = accountant;
    this.auditQuery = auditQuery;
  }

  @GetMapping("/employees")
  public ApprovedEmployeePage employees(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String companyId,
      @PageableDefault(size = 20, sort = "updatedAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return accountant.listApproved(search, companyId, pageable);
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
      @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    return auditQuery.approvalTrail(companyId, from, to, pageable);
  }
}
