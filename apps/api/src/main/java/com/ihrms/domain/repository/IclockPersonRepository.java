package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockPerson;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IclockPersonRepository extends JpaRepository<IclockPerson, String> {

  /** THE resolution query — unique by construction via {@code UNIQUE(siteId, pin)} in V44. */
  Optional<IclockPerson> findBySiteIdAndPin(String siteId, String pin);

  Optional<IclockPerson> findByEmployeeId(String employeeId);

  List<IclockPerson> findBySiteIdOrderByPinAsc(String siteId);

  List<IclockPerson> findBySiteIdAndActiveTrue(String siteId);

  long countBySiteId(String siteId);

  long countBySiteIdAndEmployeeIdIsNull(String siteId);

  /**
   * Roster people not yet linked to an IHRMS employee — the console's enrichment queue.
   */
  List<IclockPerson> findBySiteIdAndEmployeeIdIsNullOrderByNameAsc(String siteId);

  /**
   * Addresses held by more than one person at a site. Surfaced in the console as a warning; never
   * blocking, because the real seed data genuinely contains a shared address.
   */
  @Query(
      "select lower(p.email) from IclockPerson p"
          + " where p.siteId = :siteId and p.email is not null and p.email <> ''"
          + " group by lower(p.email) having count(p.id) > 1")
  List<String> findDuplicateEmails(@Param("siteId") String siteId);

  /** Case-insensitive lookup used by the HIGH-confidence link suggestion. */
  @Query("select p from IclockPerson p where p.siteId = :siteId and lower(p.email) = lower(:email)")
  List<IclockPerson> findBySiteIdAndEmailIgnoreCase(
      @Param("siteId") String siteId, @Param("email") String email);
}
