package com.ihrms.domain.repository;

import com.ihrms.domain.model.DocumentRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * HR/Accounts document requests (§8d). The employee reads their own by {@code employeeId}; the Accountant
 * reads their queue by {@code accountantUserId} with an optional status filter (via
 * {@link JpaSpecificationExecutor}). Every query is additionally tenant-filtered by {@code companyId}.
 */
public interface DocumentRequestRepository
    extends JpaRepository<DocumentRequest, String>, JpaSpecificationExecutor<DocumentRequest> {

  Page<DocumentRequest> findByEmployeeIdOrderByCreatedAtDesc(String employeeId, Pageable pageable);
}
