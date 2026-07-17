package com.ihrms.domain.repository;

import com.ihrms.domain.enums.LeaveStatus;
import com.ihrms.domain.model.LeaveRequest;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Leave requests (§8b). The employee reads their own by {@code employeeId}; the Manager reads his queue by
 * {@code managerUserId} with optional status/date filters (via {@link JpaSpecificationExecutor}). Every
 * query is additionally tenant-filtered by {@code companyId}.
 */
public interface LeaveRequestRepository
    extends JpaRepository<LeaveRequest, String>, JpaSpecificationExecutor<LeaveRequest> {

  Page<LeaveRequest> findByEmployeeIdOrderByCreatedAtDesc(String employeeId, Pageable pageable);

  /** All of one employee's leaves in a status (APPROVED for the viewer analytics; clipped in Java). */
  List<LeaveRequest> findByEmployeeIdAndStatus(String employeeId, LeaveStatus status);

  /** Batched: leaves for a set of employees in a status (team roll-up; clipped in Java). */
  List<LeaveRequest> findByEmployeeIdInAndStatus(Collection<String> employeeIds, LeaveStatus status);
}
