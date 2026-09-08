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

  /**
   * The People directory order: by NAME, with pin breaking ties.
   *
   * <p>The recency addendum exempts this screen from newest-first and asks for name order with
   * sortable columns — a directory is something you look someone up in, not a feed. Pin order was an
   * artefact of the import, and nobody looks a colleague up by biometric pin.
   *
   * <p>Nulls last: an unnamed person sorts to the bottom rather than the top, because they are a
   * cleanup task rather than the first thing an operator should meet.
   */
  @Query("SELECT p FROM IclockPerson p WHERE p.siteId = :siteId "
      + "ORDER BY CASE WHEN p.name IS NULL THEN 1 ELSE 0 END, LOWER(p.name) ASC, p.pin ASC")
  List<IclockPerson> findBySiteIdOrderByNameAscPinAsc(@Param("siteId") String siteId);

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

  /**
   * Every ACTIVE person holding this pin, across all buildings.
   *
   * <p>Pins are unique per site, not globally, so one pin can be two different people in two
   * buildings — and can also be one person who moved. Bulk deactivation uses this to say whether
   * switching somebody off here leaves them resolving somewhere else or nowhere at all.
   */
  List<IclockPerson> findByPinAndActiveTrue(String pin);

  /**
   * The team labels already in use at a building.
   *
   * <p>Teams are LABELS, not entities — there is no team table and there should not be one, because
   * the operator invents them as the floor reorganises. Offering the distinct values already present
   * is what stops "Kiran Team", "kiran team" and "Kiran's Team" becoming three teams by typo, while
   * still letting a genuinely new one be typed.
   */
  @Query("select distinct p.team from IclockPerson p "
      + "where p.siteId = :siteId and p.team is not null and p.team <> '' order by p.team")
  List<String> findDistinctTeams(@Param("siteId") String siteId);

  /** Case-insensitive lookup used by the HIGH-confidence link suggestion. */
  @Query("select p from IclockPerson p where p.siteId = :siteId and lower(p.email) = lower(:email)")
  List<IclockPerson> findBySiteIdAndEmailIgnoreCase(
      @Param("siteId") String siteId, @Param("email") String email);
}
