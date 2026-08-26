package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockPunchMember;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockPunchMemberRepository extends JpaRepository<IclockPunchMember, String> {

  /** The idempotency check: has this raw punch already been absorbed into a burst? */
  boolean existsByRawPunchId(String rawPunchId);

  Optional<IclockPunchMember> findByRawPunchId(String rawPunchId);

  long countByPunchId(String punchId);
}
