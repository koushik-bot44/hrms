package com.ihrms.offboarding.dto;

import com.ihrms.domain.enums.OffboardingStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** DTOs for the offboarding lifecycle (§Offboarding, stage 1). */
public final class OffboardingDtos {

  private OffboardingDtos() {}

  /** HR initiates an offboarding case. Both fields are required. */
  public record InitiateRequest(
      @NotBlank(message = "A reason is required") @Size(max = 1000) String reason,
      @NotNull(message = "The last working day is required") LocalDate lastWorkingDay) {}

  /** HR cancels a case (pre-completion). The note is optional. */
  public record CancelRequest(@Size(max = 1000) String note) {}

  /** The HIERARCHY approve/reject decision. On reject the note is required (enforced in the service). */
  public record DecisionRequest(@Size(max = 1000) String note) {}

  /**
   * The full case for the HR record panel (and the record-view viewers). Employee identity is already on the
   * record; this adds the case fields and the resolved actor names. Dates are ISO strings.
   */
  public record OffboardingCaseView(
      String id,
      OffboardingStatus status,
      String reason,
      String lastWorkingDay,
      String initiatedByName,
      String initiatedAt,
      String decidedByName,
      String decidedAt,
      String decisionNote,
      String cancelledByName,
      String cancelledAt,
      String cancelNote,
      // HR completion (§3.6 stage 3); null until COMPLETED.
      String completedByName,
      String completedAt,
      String completionNote,
      // Convenience flags for the UI (mirrors the service's transition rules).
      boolean cancellable) {}

  /**
   * A pending case in the HIERARCHY inbox — the MINIMAL-PII contract (§Offboarding charter loosening). ONLY
   * these fields are exposed: no forms, no documents, no contact data, nothing else about the employee.
   */
  /**
   * A single offboarding case for the HIERARCHY Offboarding tab's history (§3.6 charter).
   *
   * <p>The nine {@link HierarchyPendingRow} fields plus the four OUTCOME fields the charter extension
   * allows: {@code status, decidedByName, decidedAt, completedAt}. Nothing more — the decision, cancel
   * and completion NOTES are deliberately absent even though the row carries them, as are documents,
   * letters, contact data and any settlement value. Thirteen fields, asserted in a test.
   */
  public record HierarchyCaseRow(
      String caseId,
      String employeeName,
      String employeeCode,
      String companyName,
      String teamName,
      String reason,
      String lastWorkingDay,
      String initiatedByName,
      String initiatedAt,
      String status,
      String decidedByName,
      String decidedAt,
      String completedAt) {}

  public record HierarchyPendingRow(
      String caseId,
      String employeeName,
      String employeeCode,
      String companyName,
      String teamName,
      String reason,
      String lastWorkingDay,
      String initiatedByName,
      String initiatedAt) {}

  /** The hierarchy approve/reject outcome. */
  public record OffboardingDecisionResult(String caseId, OffboardingStatus status) {}

  /** The record-panel read: the latest case, or null when the employee has no case. Always HTTP 200. */
  public record OffboardingCaseResponse(OffboardingCaseView offboarding) {}
}
