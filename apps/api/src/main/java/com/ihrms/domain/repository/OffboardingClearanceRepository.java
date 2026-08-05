package com.ihrms.domain.repository;

import com.ihrms.domain.model.OffboardingClearance;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OffboardingClearanceRepository extends JpaRepository<OffboardingClearance, String> {

  Optional<OffboardingClearance> findByCaseId(String caseId);
}
