package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockPunch;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IclockPunchRepository extends JpaRepository<IclockPunch, String> {

  Optional<IclockPunch> findByRawPunchId(String rawPunchId);

  /**
   * Finds the burst this punch may join.
   *
   * <p><b>The window is TWO-SIDED and ordered by proximity, deliberately.</b> A one-sided
   * {@code burstLastAt >= t - window} test lets a punch join a burst lying arbitrarily far in its
   * FUTURE, which is not hypothetical: this fleet buffered ~10 minutes of punches during a DNS outage
   * and flushed them in one batch, so arrival order did not match punch order. Both edges are bounded
   * and the nearest burst wins, which makes the result independent of the order rows are processed in.
   *
   * <p>Native because ordering by absolute time distance has no clean JPQL form.
   */
  @Query(
      value =
          """
          SELECT * FROM "iclock_punches"
           WHERE "deviceId" = :deviceId
             AND "devicePin" = :devicePin
             AND "burstLastAt"  >= CAST(:punchedAt AS timestamptz)
                                   - make_interval(secs => CAST(:windowSeconds AS int))
             AND "burstFirstAt" <= CAST(:punchedAt AS timestamptz)
                                   + make_interval(secs => CAST(:windowSeconds AS int))
           ORDER BY LEAST(
                      ABS(EXTRACT(EPOCH FROM ("burstLastAt"  - CAST(:punchedAt AS timestamptz)))),
                      ABS(EXTRACT(EPOCH FROM ("burstFirstAt" - CAST(:punchedAt AS timestamptz))))) ASC
           LIMIT 1
          """,
      nativeQuery = true)
  Optional<IclockPunch> findBurstCandidate(
      @Param("deviceId") String deviceId,
      @Param("devicePin") String devicePin,
      @Param("punchedAt") Instant punchedAt,
      @Param("windowSeconds") int windowSeconds);

  /**
   * Whether the PERSON punched on a DIFFERENT device, in the SAME area, between two instants — the
   * chain-break test.
   *
   * <p><b>Keyed on personId, not employeeId, and that is load-bearing.</b> Under the identity-first
   * model most people have no linked employee, so {@code employeeId} is NULL — and {@code NULL = NULL}
   * is UNKNOWN in SQL, not true. Keying this predicate on a nullable column would silently switch the
   * chain-break test OFF for exactly the majority case: no exception, no log line, just separate
   * presentations quietly merging into one burst. personId is always present on a promoted punch.
   *
   * <p>Restricted to the same area on purpose: a CAFETERIA punch must never break a GATE burst. That
   * is why {@code area} is a parameter rather than being ignored, and it is unit-tested even though no
   * cafeteria terminal exists on this fleet yet.
   *
   * <p>The bounds are passed pre-ordered by the caller, so this is correct whether the incoming punch
   * is later or earlier than the burst it might join.
   */
  @Query(
      value =
          """
          SELECT EXISTS (
            SELECT 1 FROM "iclock_punches"
             WHERE "personId" = :personId
               AND "area" = :area
               AND "deviceId" <> :deviceId
               AND "effectiveAt" > :from
               AND "effectiveAt" < :to)
          """,
      nativeQuery = true)
  boolean existsInterveningPunch(
      @Param("personId") String personId,
      @Param("area") String area,
      @Param("deviceId") String deviceId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  Page<IclockPunch> findBySiteIdOrderByEffectiveAtDesc(String siteId, Pageable pageable);

  List<IclockPunch> findBySiteIdAndShiftDateOrderByEffectiveAtAsc(String siteId, LocalDate shiftDate);

  List<IclockPunch> findByEmployeeIdAndShiftDateOrderByEffectiveAtAsc(
      String employeeId, LocalDate shiftDate);

  /** Person Day View: one person's punches for one shift-day, in effect order. */
  List<IclockPunch> findByPersonIdAndShiftDateOrderByEffectiveAtAsc(
      String personId, LocalDate shiftDate);

  /** The Live Board's hot read: everyone at a site on a shift-day. */
  List<IclockPunch> findBySiteIdAndShiftDateOrderByEffectiveAtDesc(String siteId, LocalDate shiftDate);

  /**
   * Everyone at a site across SEVERAL shift-days at once — the board's read since V47.
   *
   * <p>A building can run more than one shift, and between 01:30 and 11:30 the day and night profiles
   * disagree about what "today" is. Fetching one date and calling it the board would silently drop
   * whichever population is on the other side of that disagreement, so the board asks for every
   * shift-day currently in play (at most one per profile) and each person is matched to their own.
   */
  List<IclockPunch> findBySiteIdAndShiftDateInOrderByEffectiveAtDesc(
      String siteId, Collection<LocalDate> shiftDates);

  /** The same set, ascending — Missing OUT needs each person's punches in the order they happened. */
  List<IclockPunch> findBySiteIdAndShiftDateInOrderByEffectiveAtAsc(
      String siteId, Collection<LocalDate> shiftDates);

  /**
   * Every punch at a site across a span of shift days — the reporting read.
   *
   * <p>Ordered so the engine receives each person's day in the order it happened, which is what the
   * session pairer assumes. Sorting per person afterwards would work too, and would be one more place
   * for the assumption to be broken silently.
   */
  List<IclockPunch> findBySiteIdAndShiftDateBetweenOrderByEffectiveAtAsc(
      String siteId, LocalDate from, LocalDate to);

  long countBySiteIdAndShiftDate(String siteId, LocalDate shiftDate);

  /** Site total across every shift-day currently in play, so a second shift is not left out of it. */
  long countBySiteIdAndShiftDateIn(String siteId, Collection<LocalDate> shiftDates);

  /** The same, per terminal — a gate serves both shifts and its card should say so. */
  long countByDeviceIdAndShiftDateIn(String deviceId, Collection<LocalDate> shiftDates);

  /**
   * Punches attributed to ONE terminal on a shift-day — the per-device figure the Overview shows.
   *
   * <p>Exists because the device card previously rendered the SITE-wide count, identically on every
   * row. With two terminals both reporting the same number, "which gate is actually seeing traffic?"
   * — the question the card is there to answer — became unanswerable, and a dead terminal would have
   * looked as busy as a live one.
   */
  long countByDeviceIdAndShiftDate(String deviceId, LocalDate shiftDate);

  long countBySiteId(String siteId);

  long countByPersonId(String personId);

  List<IclockPunch> findByAnomalyIsNotNullOrderByEffectiveAtDesc(Pageable pageable);
}
