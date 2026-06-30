package com.ihrms.domain.repository;

import com.ihrms.domain.model.Team;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {
  List<Team> findByCompanyId(String companyId);

  Optional<Team> findByIdAndCompanyId(String id, String companyId);
}
