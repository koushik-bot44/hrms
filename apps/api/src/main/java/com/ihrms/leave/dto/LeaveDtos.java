package com.ihrms.leave.dto;

import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.enums.LeaveType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/** Leave request DTOs (§8b). Dates are ISO {@code yyyy-MM-dd}; no leave balances in v1. */
public final class LeaveDtos {

  private LeaveDtos() {}

  /** Employee submits a request. {@code endDate >= startDate} is enforced in the service (400 otherwise). */
  public record SubmitLeaveRequest(
      @NotNull(message = "A start date is required") LocalDate startDate,
      @NotNull(message = "An end date is required") LocalDate endDate,
      @NotNull(message = "A leave type is required") LeaveType leaveType,
      @NotBlank(message = "A reason is required") @Size(max = 2000) String reason) {}

  /** Manager approve/reject payload. The note is REQUIRED on reject (enforced in the service). */
  public record LeaveDecisionRequest(@Size(max = 2000) String note) {}

  /** The employee's own request (it's theirs — no employee identity needed). */
  public record LeaveRequestView(
      String id,
      String startDate,
      String endDate,
      LeaveType leaveType,
      String reason,
      LeaveStatus status,
      String decisionNote,
      String decidedAt,
      String createdAt) {}

  /** A row in the Manager's queue — carries the employee's identity. */
  public record TeamLeaveRow(
      String id,
      String employeeId,
      String employeeCode,
      String employeeName,
      String startDate,
      String endDate,
      LeaveType leaveType,
      String reason,
      LeaveStatus status,
      String decisionNote,
      String decidedAt,
      String createdAt) {}

  public record LeaveMinePage(
      List<LeaveRequestView> content, int page, int size, long totalElements, int totalPages) {}

  public record TeamLeavePage(
      List<TeamLeaveRow> content, int page, int size, long totalElements, int totalPages) {}
}
