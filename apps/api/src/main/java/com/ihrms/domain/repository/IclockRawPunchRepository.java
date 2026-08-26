package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockRawPunch;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IclockRawPunchRepository extends JpaRepository<IclockRawPunch, String> {

  /** Per-device punch history, newest first — the P0 LAN verification query. */
  List<IclockRawPunch> findBySerialNumberOrderByReceivedAtDesc(String serialNumber);

  long countBySerialNumber(String serialNumber);

  /**
   * The rows actually persisted for a batch, looked up by their content keys.
   *
   * <p>Needed because the insert is {@code ON CONFLICT DO NOTHING}: a row that deduped away kept the
   * ORIGINAL punch's id, not the one this batch minted, so promotion has to work from what is really
   * in the table rather than from the ids it tried to write.
   */
  List<IclockRawPunch> findByDedupeKeyIn(Collection<String> dedupeKeys);

  /** Raw punches with no effective row yet — the promotion sweep and the operator inbox. */
  @Query(
      value =
          """
          SELECT r.* FROM "iclock_raw_punches" r
           WHERE NOT EXISTS (SELECT 1 FROM "iclock_punch_members" m WHERE m."rawPunchId" = r."id")
           ORDER BY r."punchedAtRaw" ASC, r."id" ASC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<IclockRawPunch> findUnpromoted(@Param("limit") int limit);

  /**
   * The operator inbox: pins that punched but produced no effective punch, grouped.
   *
   * <p>Returns {@code [devicePin, serialNumber, count, firstSeen, lastSeen]}. The REASON is classified
   * in Java rather than in SQL, because it depends on device claim state and pin mapping — deriving it
   * here would duplicate the promotion rules in a second place and let the two drift.
   */
  @Query(
      value =
          """
          SELECT r."devicePin", r."serialNumber", count(*) AS n,
                 min(r."receivedAt") AS first_seen, max(r."receivedAt") AS last_seen
            FROM "iclock_raw_punches" r
           WHERE NOT EXISTS (SELECT 1 FROM "iclock_punch_members" m WHERE m."rawPunchId" = r."id")
           GROUP BY r."devicePin", r."serialNumber"
           ORDER BY n DESC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<Object[]> findUnmappedPinSummary(@Param("limit") int limit);

  long countBySerialNumberAndDeviceIdIsNull(String serialNumber);
}
