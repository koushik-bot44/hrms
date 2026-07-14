package com.ihrms.domain.repository;

import com.ihrms.domain.model.LeaveRequest;
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
}
