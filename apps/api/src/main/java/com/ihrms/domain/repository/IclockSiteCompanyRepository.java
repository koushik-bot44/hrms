package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockSiteCompany;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockSiteCompanyRepository extends JpaRepository<IclockSiteCompany, String> {

  /**
   * The D2 lookup: a company belongs to exactly one site, so this is the whole employee→site
   * resolution path once you have the employee's companyId.
   */
  Optional<IclockSiteCompany> findByCompanyId(String companyId);

  List<IclockSiteCompany> findBySiteId(String siteId);

  long countBySiteId(String siteId);
}
