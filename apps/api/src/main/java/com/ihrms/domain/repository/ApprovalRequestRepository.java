package com.ihrms.domain.repository;

import com.ihrms.domain.model.ApprovalRequest;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, String> {
  List<ApprovalRequest> findByManagerUserId(String managerUserId);

  List<ApprovalRequest> findByEmployeeId(String employeeId);

  long countByTeamId(String teamId);
}
