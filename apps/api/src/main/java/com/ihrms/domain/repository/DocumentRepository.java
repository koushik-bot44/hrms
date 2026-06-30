package com.ihrms.domain.repository;

import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.model.Document;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {
  List<Document> findByEmployeeId(String employeeId);

  List<Document> findByEmployeeIdOrderByUploadedAtDesc(String employeeId);

  Optional<Document> findByIdAndEmployeeId(String id, String employeeId);

  /** How many documents the employee already has of a given type within a section (cap check). */
  int countByEmployeeIdAndSectionKeyAndDocType(
      String employeeId, SectionKey sectionKey, DocumentType docType);
}
