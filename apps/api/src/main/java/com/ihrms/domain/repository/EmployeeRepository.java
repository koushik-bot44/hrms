package com.ihrms.domain.repository;

import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.model.Employee;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmployeeRepository
    extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {
  Optional<Employee> findByEmployeeCode(String employeeCode);

  /** Employees authenticate by email (globally unique) + full name + OTP (§6). */
  Optional<Employee> findByEmail(String email);

  /** A credentialed employee by their mailbox address — the {@code /login} door for employees (§8). */
  Optional<Employee> findByMailAddress(String mailAddress);

  /** The acting HR's credentialed employees — their mail contacts (§8, Stage 5). */
  List<Employee> findByOnboardingHrIdAndMailAddressIsNotNull(String onboardingHrId);

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
}
