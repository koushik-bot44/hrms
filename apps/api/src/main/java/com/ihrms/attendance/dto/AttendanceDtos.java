package com.ihrms.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Attendance DTOs (§8a). All instants are ISO-8601 UTC strings (the client renders them in Asia/Kolkata);
 * every duration is computed SERVER-SIDE in whole seconds. springdoc-visible so the web types regenerate.
 */
public final class AttendanceDtos {

  private AttendanceDtos() {}

  /** Button state + live totals for the employee's own attendance. */
  public record ClockStatusView(
      boolean open,
      @Schema(description = "Clock-in time of the open session (ISO UTC); null when not clocked in")
          String openSince,
      long todaySeconds,
      long weekSeconds) {}

  /** One session; {@code durationSeconds} is null while the session is open (still counting). */
  public record AttendanceSessionView(
      String id, String clockInAt, String clockOutAt, Long durationSeconds) {}

  /** A day's sessions (Asia/Kolkata) + that day's total (completed sessions only). */
  public record AttendanceDayView(
      @Schema(description = "yyyy-MM-dd in Asia/Kolkata") String date,
      List<AttendanceSessionView> sessions,
      long totalSeconds) {}

  /** A page of the caller's own history, day-grouped, with the total for the whole requested range. */
  public record MyAttendancePage(
      List<AttendanceDayView> days,
      long periodSeconds,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** One row of the Manager's team roster: current state + hours. */
  public record TeamAttendanceRow(
      String employeeId,
      String employeeCode,
      String fullName,
      boolean clockedIn,
      long todaySeconds,
      long periodSeconds) {}

  /** One event in the Manager's attendance activity feed. */
  public record TeamActivityEvent(
      String employeeId,
      String employeeCode,
      String fullName,
      @Schema(description = "IN | OUT") String type,
      String at) {}

  public record TeamActivityPage(
      List<TeamActivityEvent> content, int page, int size, long totalElements, int totalPages) {}
}
