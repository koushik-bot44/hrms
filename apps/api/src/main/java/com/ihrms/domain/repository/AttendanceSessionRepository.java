package com.ihrms.domain.repository;

import com.ihrms.domain.model.AttendanceSession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttendanceSessionRepository extends JpaRepository<AttendanceSession, String> {

  /** The employee's currently-open session, if any (drives clock-in/out + status). */
  Optional<AttendanceSession> findByEmployeeIdAndClockOutAtIsNull(String employeeId);

  /** Whether the employee already has a session on a shift-day (→ this clock-in is not the first). */
  boolean existsByEmployeeIdAndShiftDate(String employeeId, LocalDate shiftDate);

  /** Whether the employee's shift-day is flagged late (its first session was late) — drives isLateToday. */
  boolean existsByEmployeeIdAndShiftDateAndLateTrue(String employeeId, LocalDate shiftDate);

  /** Completed sessions since {@code from} — for today/week totals (upper bound is "now", all in the past). */
  List<AttendanceSession> findByEmployeeIdAndClockOutAtIsNotNullAndClockInAtGreaterThanEqual(
      String employeeId, Instant from);

  /** A page of an employee's sessions in a date range (newest first via the Pageable sort) — history. */
  Page<AttendanceSession> findByEmployeeIdAndClockInAtGreaterThanEqualAndClockInAtLessThan(
      String employeeId, Instant from, Instant to, Pageable pageable);

  /** Completed sessions in a date range — the period total for that range. */
  List<AttendanceSession>
      findByEmployeeIdAndClockOutAtIsNotNullAndClockInAtGreaterThanEqualAndClockInAtLessThan(
          String employeeId, Instant from, Instant to);

  /** Which of these employees are currently clocked in (open session). Tenant-scoped by the id set. */
  List<AttendanceSession> findByEmployeeIdInAndClockOutAtIsNull(Collection<String> employeeIds);

  /** Completed sessions for a set of employees in a range — the manager roster's per-employee totals. */
  List<AttendanceSession>
      findByCompanyIdAndEmployeeIdInAndClockOutAtIsNotNullAndClockInAtGreaterThanEqualAndClockInAtLessThan(
          String companyId, Collection<String> employeeIds, Instant from, Instant to);

  // --- Viewer analytics: grouped by the persisted SHIFT-DAY (the overnight-attribution key, §8a) -----

  /** All of one employee's sessions (open + completed) whose SHIFT-DAY falls in the range — a month. */
  List<AttendanceSession> findByEmployeeIdAndShiftDateBetween(
      String employeeId, LocalDate from, LocalDate to);

  /** Batched: all sessions for a set of employees whose SHIFT-DAY falls in the range (team roll-up). */
  List<AttendanceSession> findByCompanyIdAndEmployeeIdInAndShiftDateBetween(
      String companyId, Collection<String> employeeIds, LocalDate from, LocalDate to);
}
