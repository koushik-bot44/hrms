package com.ihrms.domain.repository;

import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
import com.ihrms.domain.model.DocumentRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

  /** The employee's requests of the given types (the offboarding letters), newest first. */
  List<DocumentRequest> findByEmployeeIdAndRequestTypeInOrderByCreatedAtDesc(
      String employeeId, Collection<RequestType> types);

  Optional<DocumentRequest> findFirstByEmployeeIdAndRequestTypeOrderByCreatedAtDesc(
      String employeeId, RequestType type);

  /** Whether an open (SUBMITTED/IN_PROGRESS) request of this type already exists — one-open-per-type. */
  boolean existsByEmployeeIdAndRequestTypeAndStatusIn(
      String employeeId, RequestType type, Collection<RequestStatus> statuses);

  /** The HR's letter-request inbox (§3.6): requests routed to this HR (the routee field), newest first. */
  List<DocumentRequest> findByAccountantUserIdAndRequestTypeInOrderByCreatedAtDesc(
      String accountantUserId, Collection<RequestType> types);

  /** The pending-count for the HR letters nav badge — open letter requests routed to this HR. */
  long countByAccountantUserIdAndRequestTypeInAndStatusIn(
      String accountantUserId, Collection<RequestType> types, Collection<RequestStatus> statuses);
}
