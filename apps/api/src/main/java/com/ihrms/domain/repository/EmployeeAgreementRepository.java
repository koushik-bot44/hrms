package com.ihrms.domain.repository;

import com.ihrms.domain.enums.EmployeeAgreementType;
import com.ihrms.domain.model.EmployeeAgreement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeAgreementRepository extends JpaRepository<EmployeeAgreement, String> {
  List<EmployeeAgreement> findByEmployeeIdOrderByTypeAsc(String employeeId);

  List<EmployeeAgreement> findByEmployeeId(String employeeId);

  Optional<EmployeeAgreement> findByEmployeeIdAndType(String employeeId, EmployeeAgreementType type);

  boolean existsByEmployeeId(String employeeId);
}
