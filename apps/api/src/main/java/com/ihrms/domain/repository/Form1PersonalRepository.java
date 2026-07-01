package com.ihrms.domain.repository;

import com.ihrms.domain.model.Form1Personal;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Form1PersonalRepository extends JpaRepository<Form1Personal, String> {
  Optional<Form1Personal> findByEmployeeId(String employeeId);
}
