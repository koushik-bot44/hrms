package com.ihrms.domain.repository;

import com.ihrms.domain.model.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
  Optional<User> findByEmail(String email);

  List<User> findByCompanyId(String companyId);

  List<User> findByCompanyIdAndRole(String companyId, com.ihrms.domain.enums.UserRole role);
}
