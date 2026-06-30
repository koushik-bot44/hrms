package com.ihrms.domain.repository;

import com.ihrms.domain.model.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {
  List<Team> findByCompanyId(String companyId);

  List<Team> findByCompanyIdOrderByCreatedAtDesc(String companyId);

  long countByCompanyId(String companyId);

  Optional<Team> findByIdAndCompanyId(String id, String companyId);

  /** The HR's own team (its Manager is the approver for that HR's onboarded employees, §2). */
  Optional<Team> findByCompanyIdAndHrUserId(String companyId, String hrUserId);

  /** True iff this manager manages a team whose HR onboarded the employee (§6 manager scope). */
  boolean existsByCompanyIdAndManagerUserIdAndHrUserId(
      String companyId, String managerUserId, String hrUserId);
}
