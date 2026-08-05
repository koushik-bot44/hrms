package com.ihrms.domain.repository;

import com.ihrms.domain.model.EmployeeOffer;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeOfferRepository extends JpaRepository<EmployeeOffer, String> {

  Optional<EmployeeOffer> findByEmployeeId(String employeeId);
}
