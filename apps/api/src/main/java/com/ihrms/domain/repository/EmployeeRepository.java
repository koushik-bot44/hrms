package com.ihrms.domain.repository;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Employee;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeRepository
    extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {
  Optional<Employee> findByEmployeeCode(String employeeCode);

  /** Employees authenticate by email (globally unique) + full name + OTP (§6). */
  Optional<Employee> findByEmail(String email);

  /** A credentialed employee by their mailbox address — the {@code /login} door for employees (§8). */
  Optional<Employee> findByMailAddress(String mailAddress);

  /** The acting HR's credentialed employees — their mail contacts (§8, Stage 5). */
  List<Employee> findByOnboardingHrIdAndMailAddressIsNotNull(String onboardingHrId);

  /** All CREDENTIALED employees in a company — the Company Admin's employee mail contacts (§8). */
  List<Employee> findByCompanyIdAndMailAddressIsNotNull(String companyId);

  List<Employee> findByCompanyIdAndOnboardingHrId(String companyId, String onboardingHrId);

  List<Employee> findByCompanyIdAndOnboardingHrIdOrderByCreatedAtDesc(
      String companyId, String onboardingHrId);

  long countByCompanyId(String companyId);

  // --- Dashboard counts (scoped exactly like the list views; COUNT, not fetch-all) ---
  long countByCompanyIdAndStatus(String companyId, EmployeeStatus status);

  /** Portal-wide count by status — the ACCOUNTANT's cross-company (READ-only) breadth. */
  long countByStatus(EmployeeStatus status);

  long countByOnboardingHrId(String onboardingHrId);

  long countByOnboardingHrIdAndStatus(String onboardingHrId, EmployeeStatus status);

  long countByOnboardingHrIdAndStatusIn(String onboardingHrId, Collection<EmployeeStatus> statuses);

  long countByOnboardingHrIdIn(Collection<String> onboardingHrIds);

  long countByOnboardingHrIdInAndStatus(Collection<String> onboardingHrIds, EmployeeStatus status);

  long countByOnboardingHrIdInAndStatusNot(Collection<String> onboardingHrIds, EmployeeStatus status);

  // --- Dashboard activity (scoped recent records by updatedAt) ---
  List<Employee> findTop10ByOrderByUpdatedAtDesc();

  List<Employee> findTop10ByCompanyIdOrderByUpdatedAtDesc(String companyId);

  List<Employee> findTop10ByOnboardingHrIdOrderByUpdatedAtDesc(String onboardingHrId);

  List<Employee> findTop10ByOnboardingHrIdInOrderByUpdatedAtDesc(Collection<String> onboardingHrIds);

  /** Recent employees of a given status across ALL companies (ACCOUNTS_ADMIN activity feed). */
  List<Employee> findTop10ByStatusOrderByUpdatedAtDesc(EmployeeStatus status);

  /** Employees onboarded by any of these HRs — the team Accountant's employee set (for its audit). */
  List<Employee> findByOnboardingHrIdIn(java.util.Collection<String> onboardingHrIds);

  // --- Hierarchy platform aggregates (§2; cross-company, counts-only, no PII) ---

  /** Platform onboarding funnel: {@code [EmployeeStatus, Long]} rows for every status present. */
  @Query("select e.status, count(e) from Employee e group by e.status")
  List<Object[]> countGroupByStatus();

  /** Employees per company: {@code [companyId, Long]} rows (company-size distribution). */
  @Query("select e.companyId, count(e) from Employee e group by e.companyId")
  List<Object[]> countGroupByCompany();

  /** One company's onboarding funnel: {@code [EmployeeStatus, Long]} rows. */
  @Query("select e.status, count(e) from Employee e where e.companyId = :companyId group by e.status")
  List<Object[]> countByCompanyGroupByStatus(@Param("companyId") String companyId);

  /** One company's employees per onboarding HR: {@code [onboardingHrId, Long]} (team employee counts). */
  @Query("select e.onboardingHrId, count(e) from Employee e where e.companyId = :companyId group by e.onboardingHrId")
  List<Object[]> countByCompanyGroupByHr(@Param("companyId") String companyId);

  /**
   * Stuck onboardings, by stage: {@code [EmployeeStatus, Long]} for employees in a PRE-APPROVAL state
   * whose {@code createdAt} is older than {@code before} (the stuck threshold). Sum = total stuck.
   */
  @Query("select e.status, count(e) from Employee e where e.status in :statuses and e.createdAt < :before group by e.status")
  List<Object[]> countStuckByStage(
      @Param("statuses") Collection<EmployeeStatus> statuses, @Param("before") Instant before);

  /**
   * Employees ONBOARDED per IST month: {@code [ "YYYY-MM", Long ]}. Bucketed by {@code createdAt}
   * converted UTC → Asia/Kolkata (fixed +05:30). Native — the column is a naked-UTC {@code timestamp}.
   */
  @Query(
      value =
          "SELECT to_char(\"createdAt\" AT TIME ZONE 'UTC' AT TIME ZONE 'Asia/Kolkata', 'YYYY-MM') AS ym,"
              + " COUNT(*) FROM \"employees\" GROUP BY ym",
      nativeQuery = true)
  List<Object[]> joinedPerIstMonth();
}
