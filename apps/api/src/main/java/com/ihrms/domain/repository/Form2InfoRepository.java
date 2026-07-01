package com.ihrms.domain.repository;

import com.ihrms.domain.model.Form2Info;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Form2InfoRepository extends JpaRepository<Form2Info, String> {
  Optional<Form2Info> findByEmployeeId(String employeeId);
}
