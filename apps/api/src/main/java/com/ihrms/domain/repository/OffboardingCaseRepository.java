package com.ihrms.domain.repository;

import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.model.OffboardingCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OffboardingCaseRepository extends JpaRepository<OffboardingCase, String> {

  /** The employee's non-terminal case (PENDING_APPROVAL or APPROVED), if any — the one-active-case invariant. */
  Optional<OffboardingCase> findFirstByEmployeeIdAndStatusIn(
      String employeeId, List<OffboardingStatus> statuses);

  /** The employee's latest case (any status) — drives the HR record panel. */
  Optional<OffboardingCase> findFirstByEmployeeIdOrderByInitiatedAtDesc(String employeeId);

  /** All cases in a status, newest first — the hierarchy pending inbox. */
  List<OffboardingCase> findByStatusOrderByInitiatedAtDesc(OffboardingStatus status);

  long countByStatus(OffboardingStatus status);
}
