package com.ihrms.audit.dto;

import java.util.List;
import java.util.Map;

/** Audit explorer DTOs (ARCHITECTURE.md §7). springdoc-visible (referenced by AuditController). */
public final class AuditDtos {

  private AuditDtos() {}

  /**
   * One audit row. {@code actorLabel} / {@code companyName} / {@code targetLabel} are resolved,
   * human-readable names (the raw cuid {@code actorId} / {@code companyId} / {@code targetId} stay
   * too). {@code targetLabel} is the employee's name when the target is an Employee, else null.
   */
  public record AuditLogView(
      String id,
      String companyId,
      String companyName,
      String actorType,
      String actorId,
      String actorLabel,
      String action,
      String targetType,
      String targetId,
      String targetLabel,
      Map<String, Object> metadata,
      String ipAddress,
      String createdAt) {}

  /**
   * A page of audit rows for one company (Super Admin views each company separately).
   * {@code companyDeleted} flags that the selected company has been archived (its trail is retained).
   */
  public record AuditPage(
      List<AuditLogView> content,
      int page,
      int size,
      long totalElements,
      int totalPages,
      boolean companyDeleted) {}
}
