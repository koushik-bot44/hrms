package com.ihrms.domain.repository;

import com.ihrms.domain.enums.RequestType;
import com.ihrms.domain.model.OffboardingLetter;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OffboardingLetterRepository extends JpaRepository<OffboardingLetter, String> {

  List<OffboardingLetter> findByCaseId(String caseId);

  Optional<OffboardingLetter> findByCaseIdAndType(String caseId, RequestType type);
}
