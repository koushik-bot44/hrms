package com.ihrms.domain.repository;

import com.ihrms.domain.enums.ApprovalStatus;
import com.ihrms.domain.model.ApprovalRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {
  List<ApprovalRequest> findByManagerUserId(String managerUserId);

  /** A Manager's queue for a given status (oldest first), scoped to their own team's requests. */
  List<ApprovalRequest> findByManagerUserIdAndStatusOrderBySubmittedAtAsc(
      String managerUserId, ApprovalStatus status);

  Optional<ApprovalRequest> findByIdAndManagerUserId(String id, String managerUserId);

  List<ApprovalRequest> findByEmployeeId(String employeeId);

  long countByTeamId(String teamId);
}
