package com.ihrms.domain.repository;

import com.ihrms.domain.model.Employee;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface EmployeeRepository
    extends JpaRepository<Employee, String>, JpaSpecificationExecutor<Employee> {
  Optional<Employee> findByEmployeeCode(String employeeCode);

  /** Employees authenticate by email (globally unique) + full name + OTP (§6). */
  Optional<Employee> findByEmail(String email);

  List<Employee> findByCompanyIdAndOnboardingHrId(String companyId, String onboardingHrId);

  List<Employee> findByCompanyIdAndOnboardingHrIdOrderByCreatedAtDesc(
      String companyId, String onboardingHrId);

  long countByCompanyId(String companyId);
}
