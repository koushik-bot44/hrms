package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockEmployeePin;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IclockEmployeePinRepository extends JpaRepository<IclockEmployeePin, String> {

  /** One pin per employee (unique index). */
  Optional<IclockEmployeePin> findByEmployeeId(String employeeId);

  /**
   * THE resolution query. Unique by construction — {@code UNIQUE(siteId, pin)} in V43 means at most one
   * row can match, so a pin collision is a constraint violation at write time rather than an ambiguous
   * read here.
   */
  Optional<IclockEmployeePin> findBySiteIdAndPin(String siteId, String pin);

  List<IclockEmployeePin> findBySiteIdOrderByPinAsc(String siteId);

  long countBySiteId(String siteId);

  /**
   * Re-points every pin of a company's employees at a new site, for a site re-link. Runs in the SAME
   * transaction as the link change: the denormalised {@code siteId} is the scope the unique index
   * guards, so leaving it stale would let the constraint protect a scope that no longer exists. A
   * re-link that would create a collision fails here on the unique index — which is the intended,
   * loud outcome.
   */
  @Modifying
  @Query(
      "update IclockEmployeePin p set p.siteId = :siteId"
          + " where p.employeeId in (select e.id from Employee e where e.companyId = :companyId)")
  int repointCompanyPins(@Param("companyId") String companyId, @Param("siteId") String siteId);

  /**
   * Pins whose denormalised site no longer matches their employee's company membership — should always
   * be empty. A standing integrity probe for the one invariant V43 cannot express declaratively.
   */
  @Query(
      "select p from IclockEmployeePin p, Employee e, IclockSiteCompany sc"
          + " where p.employeeId = e.id and sc.companyId = e.companyId and p.siteId <> sc.siteId")
  List<IclockEmployeePin> findDriftedPins();
}
