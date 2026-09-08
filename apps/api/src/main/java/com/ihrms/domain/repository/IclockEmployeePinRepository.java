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
   * Re-points a company's pins THAT ARE CURRENTLY AT {@code fromSiteId} to {@code siteId}.
   *
   * <p><b>The site filter is the whole point, and it was missing.</b> The old form updated every pin
   * of every employee of the company with no site predicate. Under D2 that was correct, because a
   * company had one site and a re-link moved the lot. Once a company can span two buildings it
   * becomes destructive: linking Screatives to Building 9 would have dragged Orion Towers' pins along
   * with it, either silently — every Orion pin now claiming to live in Building 9 — or as a unique
   * violation on (siteId, pin) that rolls back a legitimate operation with a 500.
   *
   * <p>An adversarial review called this unreachable, and it was: {@code linkCompany} refused the
   * second building before ever getting here. Removing that refusal is the entire purpose of this
   * change, which is exactly what makes the hazard live. The two had to move together.
   */
  @Modifying
  @Query(
      "update IclockEmployeePin p set p.siteId = :siteId"
          + " where p.siteId = :fromSiteId"
          + " and p.employeeId in (select e.id from Employee e where e.companyId = :companyId)")
  int repointCompanyPins(
      @Param("companyId") String companyId,
      @Param("fromSiteId") String fromSiteId,
      @Param("siteId") String siteId);

  /**
   * Pins whose denormalised site no longer matches their employee's company membership — should always
   * be empty. A standing integrity probe for the one invariant V43 cannot express declaratively.
   */
  @Query(
      "select p from IclockEmployeePin p, Employee e, IclockSiteCompany sc"
          + " where p.employeeId = e.id and sc.companyId = e.companyId and p.siteId <> sc.siteId")
  List<IclockEmployeePin> findDriftedPins();
}
