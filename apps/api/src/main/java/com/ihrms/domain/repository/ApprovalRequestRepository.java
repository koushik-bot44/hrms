package com.ihrms.domain.repository;

import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.model.ApprovalRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {
  List<ApprovalRequest> findByManagerUserId(String managerUserId);

  /** A Manager's queue for a given status (oldest first), scoped to their own team's requests. */
  List<ApprovalRequest> findByManagerUserIdAndStatusOrderBySubmittedAtAsc(
      String managerUserId, ApprovalStatus status);

  /** A Manager's decided approvals (most recently decided first) for the history view. */
  List<ApprovalRequest> findByManagerUserIdAndStatusInOrderByDecidedAtDesc(
      String managerUserId, Collection<ApprovalStatus> statuses);

  Optional<ApprovalRequest> findByIdAndManagerUserId(String id, String managerUserId);

  List<ApprovalRequest> findByEmployeeId(String employeeId);

  long countByTeamId(String teamId);

  // --- Dashboard counts ---
  long countByManagerUserIdAndStatus(String managerUserId, ApprovalStatus status);

  /** Portfolio-wide pending approvals (Super Admin summary). */
  long countByStatus(ApprovalStatus status);

  // --- Hierarchy platform aggregates (§2; the approval decision is the "approved date" source) ---

  /**
   * Employees APPROVED per IST month: {@code [ "YYYY-MM", Long ]}. Bucketed by the approval's
   * {@code decidedAt} (status APPROVED) converted UTC → Asia/Kolkata. Native — naked-UTC {@code timestamp}.
   */
  @Query(
      value =
          "SELECT to_char(\"decidedAt\" AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS ym,"
              + " COUNT(*) FROM \"approval_requests\" WHERE \"status\" = 'APPROVED' AND \"decidedAt\" IS NOT NULL"
              + " GROUP BY ym",
      nativeQuery = true)
  List<Object[]> approvedPerIstMonth();

  /**
   * Mean seconds between an employee's {@code createdAt} (onboard) and their approval's {@code decidedAt},
   * over APPROVED employees — the basis for averageTimeToApprovalDays. {@code null} when none approved.
   */
  @Query(
      value =
          "SELECT AVG(EXTRACT(EPOCH FROM (a.\"decidedAt\" - e.\"createdAt\"))) FROM \"approval_requests\" a"
              + " JOIN \"employees\" e ON a.\"employeeId\" = e.\"id\""
              + " WHERE a.\"status\" = 'APPROVED' AND a.\"decidedAt\" IS NOT NULL",
      nativeQuery = true)
  Double avgSecondsToApproval();
}
