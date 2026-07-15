package com.ihrms.attendance.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Attendance DTOs (§8a). All instants are ISO-8601 UTC strings (the client renders them in Asia/Kolkata);
 * every duration is computed SERVER-SIDE in whole seconds and worked time EXCLUDES break time.
 * springdoc-visible so the web types regenerate.
 */
public final class AttendanceDtos {

  private AttendanceDtos() {}

  /** Button state + live totals for the employee's own attendance (drives the buttons + the entry prompts). */
  public record ClockStatusView(
      boolean open,
      @Schema(description = "Clock-in time of the open session (ISO UTC); null when not clocked in")
          String openSince,
      boolean onBreak,
      @Schema(description = "Start time of the open break (ISO UTC); null when not on a break")
          String breakOpenSince,
      @Schema(description = "Whether the first clock-in of the current shift-day was late (after 19:20 IST)")
          boolean isLateToday,
      @Schema(description = "Worked seconds today (this shift-day), completed sessions minus breaks")
          long todaySeconds,
      @Schema(description = "Worked seconds this week, completed sessions minus breaks") long weekSeconds) {}

  /** One break within a session; {@code durationSeconds} is null while the break is open. */
  public record AttendanceBreakView(
      String id, String breakStartAt, String breakEndAt, Long durationSeconds) {}

  /** One session; {@code workedSeconds} (duration minus breaks) is null while open (still counting). */
  public record AttendanceSessionView(
      String id,
      String clockInAt,
      String clockOutAt,
      @Schema(description = "Worked seconds = duration minus breaks; null while the session is open")
          Long workedSeconds,
      boolean isLate,
      Integer lateMinutes,
      List<AttendanceBreakView> breaks) {}

  /**
   * A shift-day's sessions + that day's worked total (completed sessions, minus breaks); {@code late} when
   * its first clock-in was late.
   */
  public record AttendanceDayView(
      @Schema(description = "shift-day yyyy-MM-dd in Asia/Kolkata (the day the shift started)") String date,
      List<AttendanceSessionView> sessions,
      long totalSeconds,
      boolean late) {}

  /** A page of the caller's own history, grouped by shift-day, with the worked total for the whole range. */
  public record MyAttendancePage(
      List<AttendanceDayView> days,
      long periodSeconds,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /** One row of the Manager's team roster: current state + worked hours + late-today. */
  public record TeamAttendanceRow(
      String employeeId,
      String employeeCode,
      String fullName,
      boolean clockedIn,
      boolean onBreak,
      boolean lateToday,
      long todaySeconds,
      long periodSeconds) {}

  /** One event in the Manager's attendance activity feed. */
  public record TeamActivityEvent(
      String employeeId,
      String employeeCode,
      String fullName,
      @Schema(description = "IN | OUT | BREAK_START | BREAK_END") String type,
      String at) {}

  public record TeamActivityPage(
      List<TeamActivityEvent> content, int page, int size, long totalElements, int totalPages) {}
}
