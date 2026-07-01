package com.ihrms.domain.repository;

import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.model.Document;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {
  List<Document> findByEmployeeId(String employeeId);

  List<Document> findByEmployeeIdOrderByUploadedAtDesc(String employeeId);

  Optional<Document> findByIdAndEmployeeId(String id, String employeeId);

  /** How many files the employee already has in a given Form 4 slot (group-aware cap check). */
  int countByEmployeeIdAndDocTypeAndGroupIndex(
      String employeeId, DocumentType docType, Integer groupIndex);
}
