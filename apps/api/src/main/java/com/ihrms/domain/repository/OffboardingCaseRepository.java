package com.ihrms.domain.repository;

import com.ihrms.domain.enums.OffboardingStatus;
import com.ihrms.domain.model.OffboardingCase;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OffboardingCaseRepository extends JpaRepository<OffboardingCase, String> {

  /** The employee's non-terminal case (PENDING_APPROVAL or APPROVED), if any — the one-active-case invariant. */
  Optional<OffboardingCase> findFirstByEmployeeIdAndStatusIn(
      String employeeId, List<OffboardingStatus> statuses);

  /** The employee's latest case (any status) — drives the HR record panel. */
  Optional<OffboardingCase> findFirstByEmployeeIdOrderByInitiatedAtDesc(String employeeId);

  /** All cases in a status, newest first — the hierarchy pending inbox. */
  List<OffboardingCase> findByStatusOrderByInitiatedAtDesc(OffboardingStatus status);

  long countByStatus(OffboardingStatus status);

  /*
   * The HIERARCHY Offboarding tab's history (§3.6): newest first, narrowed by company / initiated-date
   * window / status.
   *
   * Two methods rather than one with a nullable :employeeIds, and a status SET rather than a nullable
   * :status — because no parameter here is ever null. Postgres cannot infer the type of a null enum or
   * a null collection in these positions and fails the whole query ("could not determine data type"),
   * and a cast does not rescue it. Making the absence structural is sturdier than arguing with the
   * driver: "all statuses" is the full set, and "all companies" is simply the other method.
   */

  /** Every company. */
  @Query(
      """
      select c from OffboardingCase c
      where c.initiatedAt >= :from
        and c.initiatedAt <= :to
        and c.status in :statuses
      order by c.initiatedAt desc
      """)
  List<OffboardingCase> searchForHierarchy(
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("statuses") Collection<OffboardingStatus> statuses);

  /** One company, expressed as its employees (the caller resolves it; never an empty collection). */
  @Query(
      """
      select c from OffboardingCase c
      where c.employeeId in :employeeIds
        and c.initiatedAt >= :from
        and c.initiatedAt <= :to
        and c.status in :statuses
      order by c.initiatedAt desc
      """)
  List<OffboardingCase> searchForHierarchyInEmployees(
      @Param("employeeIds") Collection<String> employeeIds,
      @Param("from") Instant from,
      @Param("to") Instant to,
      @Param("statuses") Collection<OffboardingStatus> statuses);

  /** Completed cases grouped by IST month of {@code completedAt} — the hierarchy trends' offboarded series. */
  @Query(
      value =
          "SELECT to_char(\"completedAt\" AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS ym,"
              + " COUNT(*) FROM \"offboarding_cases\" WHERE \"completedAt\" IS NOT NULL GROUP BY ym",
      nativeQuery = true)
  List<Object[]> completedPerIstMonth();
}
