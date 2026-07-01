package com.ihrms.domain.repository;

import com.ihrms.domain.model.Form3PrevEmployment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface Form3PrevEmploymentRepository extends JpaRepository<Form3PrevEmployment, String> {
  List<Form3PrevEmployment> findByEmployeeIdOrderByOrderIndexAsc(String employeeId);
}
