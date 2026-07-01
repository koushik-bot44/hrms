package com.ihrms.domain.repository;

import com.ihrms.domain.model.Signature;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignatureRepository extends JpaRepository<Signature, String> {
  Optional<Signature> findByEmployeeId(String employeeId);
}
