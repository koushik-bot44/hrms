package com.ihrms.domain.repository;

import com.ihrms.domain.model.EmployeeCodeSequence;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeCodeSequenceRepository extends JpaRepository<EmployeeCodeSequence, String> {
  Optional<EmployeeCodeSequence> findByCompanyId(String companyId);
}
