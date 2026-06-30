package com.ihrms.domain.repository;

import com.ihrms.domain.model.Document;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, String> {
  List<Document> findByEmployeeId(String employeeId);

  Optional<Document> findByIdAndEmployeeId(String id, String employeeId);
}
