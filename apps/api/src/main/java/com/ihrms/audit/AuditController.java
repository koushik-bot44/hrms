package com.ihrms.audit;

import com.ihrms.audit.dto.AuditDtos.AuditPage;
import com.ihrms.auth.IhrmsPrincipal;
import java.time.Instant;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-company audit explorer (contract §7). SUPER_ADMIN reads any company's trail (one company per
 * query — kept separated); COMPANY_ADMIN is locked to their own company. Read-only.
 */
@RestController
@RequestMapping("/audit")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','COMPANY_ADMIN')")
public class AuditController {

  private final AuditQueryService audit;

  public AuditController(AuditQueryService audit) {
    this.audit = audit;
  }

  @GetMapping
  public AuditPage query(
      @RequestParam(required = false) String companyId,
      @RequestParam(required = false) String action,
      @RequestParam(required = false) String actorType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC)
          Pageable pageable,
      @AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return audit.query(actor, companyId, action, actorType, from, to, pageable);
  }
}
