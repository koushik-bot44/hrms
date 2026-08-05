package com.ihrms.domain.repository;

import com.ihrms.domain.enums.OffboardingDocType;
import com.ihrms.domain.model.OffboardingDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OffboardingDocumentRepository extends JpaRepository<OffboardingDocument, String> {

  List<OffboardingDocument> findByCaseIdOrderByTypeAsc(String caseId);

  List<OffboardingDocument> findByCaseId(String caseId);

  Optional<OffboardingDocument> findByCaseIdAndType(String caseId, OffboardingDocType type);
}
