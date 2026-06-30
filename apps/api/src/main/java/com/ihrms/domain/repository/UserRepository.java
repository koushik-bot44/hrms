package com.ihrms.domain.repository;

import com.ihrms.domain.enums.UserRole;
import com.ihrms.domain.model.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, String> {
  Optional<User> findByEmail(String email);

  List<User> findByCompanyId(String companyId);

  List<User> findByCompanyIdAndRole(String companyId, UserRole role);

  Optional<User> findByIdAndCompanyId(String id, String companyId);

  boolean existsByCompanyIdAndRole(String companyId, UserRole role);

  /** The company's admin (first by creation), if provisioned. */
  Optional<User> findFirstByCompanyIdAndRoleOrderByCreatedAtAsc(String companyId, UserRole role);

  /** Members of a team (users whose teamId points at it). */
  long countByTeamId(String teamId);

  List<User> findByTeamIdOrderByNameAsc(String teamId);

  /** Unassigned company users of a role — candidates for an HR/Manager slot. */
  List<User> findByCompanyIdAndRoleAndTeamIdIsNullOrderByNameAsc(String companyId, UserRole role);
}
