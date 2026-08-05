package com.ihrms.domain.repository;

import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.model.OffboardingCase;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OffboardingCaseRepository extends JpaRepository<OffboardingCase, String> {

  /** The employee's non-terminal case (PENDING_APPROVAL or APPROVED), if any — the one-active-case invariant. */
  Optional<OffboardingCase> findFirstByEmployeeIdAndStatusIn(
      String employeeId, List<OffboardingStatus> statuses);

  /** The employee's latest case (any status) — drives the HR record panel. */
  Optional<OffboardingCase> findFirstByEmployeeIdOrderByInitiatedAtDesc(String employeeId);

  /** All cases in a status, newest first — the hierarchy pending inbox. */
  List<OffboardingCase> findByStatusOrderByInitiatedAtDesc(OffboardingStatus status);

  long countByStatus(OffboardingStatus status);

  /** Completed cases grouped by IST month of {@code completedAt} — the hierarchy trends' offboarded series. */
  @Query(
      value =
          "SELECT to_char(\"completedAt\" AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS ym,"
              + " COUNT(*) FROM \"offboarding_cases\" WHERE \"completedAt\" IS NOT NULL GROUP BY ym",
      nativeQuery = true)
  List<Object[]> completedPerIstMonth();
}
