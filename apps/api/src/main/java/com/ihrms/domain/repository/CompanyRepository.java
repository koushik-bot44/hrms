package com.ihrms.domain.repository;

import com.ihrms.domain.model.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, String> {
  Optional<Company> findByCode(String code);

  List<Company> findAllByOrderByCreatedAtDesc();

  // --- Dashboard counts (status is a free string; DELETED = archived) ---
  long countByStatus(String status);

  long countByStatusNot(String status);
}
