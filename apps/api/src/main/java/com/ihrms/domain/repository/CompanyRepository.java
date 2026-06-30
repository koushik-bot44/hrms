package com.ihrms.domain.repository;

import com.ihrms.domain.model.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, String> {
  Optional<Company> findByCode(String code);
}
