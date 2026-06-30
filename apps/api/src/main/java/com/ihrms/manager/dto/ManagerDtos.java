package com.ihrms.manager.dto;

import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.NotificationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Manager inbox DTOs (ARCHITECTURE.md §2/§3.3). springdoc-visible (referenced by ManagerController)
 * so /v3/api-docs reflects them and the web types regenerate.
 */
public final class ManagerDtos {

  private ManagerDtos() {}

  public record NotificationView(
      String id,
      NotificationType type,
      String employeeId,
      String employeeCode,
      String fullName,
      boolean read,
      String createdAt) {}

  public record NotificationFeed(List<NotificationView> notifications, long unreadCount) {}

  /**
   * A pending (or decided) approval with the employee summary + who onboarded them.
   * {@code employeeCode} is null until the Manager approves (the ID is minted then, §5).
   */
  public record ApprovalView(
      String id,
      ApprovalStatus status,
      String note,
      String submittedAt,
      String decidedAt,
      String employeeCode,
      String fullName,
      String employeeEmail,
      String designation,
      EmployeeStatus employeeStatus,
      String hrName) {}

  /** Rejecting an approval requires a note (sent back to HR + recorded). */
  public record RejectApprovalRequest(
      @NotBlank(message = "A note is required when rejecting")
          @Size(max = 500, message = "Note is too long")
          String note) {}
}
