package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockSiteCompany;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IclockSiteCompanyRepository extends JpaRepository<IclockSiteCompany, String> {

  /**
   * Every site a company works in.
   *
   * <p>Was {@code Optional} under D2 ("a company belongs to exactly one site"). That premise is now
   * false — Screatives and Sphinix both span two buildings — and the Optional form was not merely
   * inexpressive, it was a fault: Spring Data raises IncorrectResultSizeDataAccessException on a
   * second row, which no handler covers, so the admin surface would have degraded to opaque 500s the
   * moment a company was linked twice.
   */
  List<IclockSiteCompany> findByCompanyId(String companyId);

  /** The site-scoped lookup, which is what almost every caller actually wants. */
  Optional<IclockSiteCompany> findBySiteIdAndCompanyId(String siteId, String companyId);

  boolean existsBySiteIdAndCompanyId(String siteId, String companyId);

  List<IclockSiteCompany> findBySiteId(String siteId);

  long countBySiteId(String siteId);
}
