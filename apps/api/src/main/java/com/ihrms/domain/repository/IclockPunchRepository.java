package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockPunch;
import java.time.Instant;
import java.time.LocalDate;
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
   * Whether the employee punched on a DIFFERENT device, in the SAME area, between two instants — the
   * chain-break test.
   *
   * <p>Restricted to the same area on purpose: a CAFETERIA punch must never break a GATE burst. That
   * is the whole reason {@code area} is a parameter rather than being ignored, and it is unit-tested
   * even though no cafeteria terminal exists on this fleet yet.
   *
   * <p>The bounds are passed pre-ordered by the caller, so this is correct whether the incoming punch
   * is later or earlier than the burst it might join.
   */
  @Query(
      value =
          """
          SELECT EXISTS (
            SELECT 1 FROM "iclock_punches"
             WHERE "employeeId" = :employeeId
               AND "area" = :area
               AND "deviceId" <> :deviceId
               AND "effectiveAt" > :from
               AND "effectiveAt" < :to)
          """,
      nativeQuery = true)
  boolean existsInterveningPunch(
      @Param("employeeId") String employeeId,
      @Param("area") String area,
      @Param("deviceId") String deviceId,
      @Param("from") Instant from,
      @Param("to") Instant to);

  Page<IclockPunch> findBySiteIdOrderByEffectiveAtDesc(String siteId, Pageable pageable);

  List<IclockPunch> findBySiteIdAndShiftDateOrderByEffectiveAtAsc(String siteId, LocalDate shiftDate);

  List<IclockPunch> findByEmployeeIdAndShiftDateOrderByEffectiveAtAsc(
      String employeeId, LocalDate shiftDate);

  long countBySiteId(String siteId);

  List<IclockPunch> findByAnomalyIsNotNullOrderByEffectiveAtDesc(Pageable pageable);
}
