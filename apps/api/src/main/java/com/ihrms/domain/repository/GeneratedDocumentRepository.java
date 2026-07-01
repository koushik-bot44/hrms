package com.ihrms.domain.repository;

import com.ihrms.domain.model.GeneratedDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, String> {
  List<GeneratedDocument> findByEmployeeIdOrderByKindAsc(String employeeId);

  List<GeneratedDocument> findByEmployeeId(String employeeId);

  Optional<GeneratedDocument> findByIdAndEmployeeId(String id, String employeeId);
}
