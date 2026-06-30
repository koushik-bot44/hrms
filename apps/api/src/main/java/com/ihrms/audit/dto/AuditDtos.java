package com.ihrms.audit.dto;

import java.util.List;
import java.util.Map;

/** Audit explorer DTOs (ARCHITECTURE.md §7). springdoc-visible (referenced by AuditController). */
public final class AuditDtos {

  private AuditDtos() {}

  /** One audit row. {@code actorLabel} is the resolved actor name/code (cuid actorId stays too). */
  public record AuditLogView(
      String id,
      String companyId,
      String actorType,
      String actorId,
      String actorLabel,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata,
      String ipAddress,
      String createdAt) {}

  /** A page of audit rows for one company (Super Admin views each company separately). */
  public record AuditPage(
      List<AuditLogView> content, int page, int size, long totalElements, int totalPages) {}
}
