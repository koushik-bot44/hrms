package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockSitePolicy;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockSitePolicyRepository extends JpaRepository<IclockSitePolicy, String> {

  /** One policy row per building; absence means "use the defaults", never an error. */
  Optional<IclockSitePolicy> findBySiteId(String siteId);
}
