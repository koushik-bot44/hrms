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

  /**
   * Unpromoted raw punches received at or after an instant — the scoped re-resolution window.
   *
   * <p>Bounded by the device's {@code claimedAt} rather than sweeping all history: punches that
   * arrived after adoption were declined only because no roster existed yet, whereas the archive
   * predates adoption entirely and re-deriving it is a separate, explicit decision.
   *
   * <p>Ordered by the DEVICE's clock, not arrival, so a buffered flush promotes the way it would have
   * live.
   */
  @Query(
      value =
          """
          SELECT r.* FROM "iclock_raw_punches" r
           WHERE r."receivedAt" >= CAST(:since AS timestamptz)
             AND NOT EXISTS (SELECT 1 FROM "iclock_punch_members" m WHERE m."rawPunchId" = r."id")
           ORDER BY r."punchedAtRaw" ASC, r."id" ASC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<IclockRawPunch> findUnpromotedSince(
      @Param("since") java.time.Instant since, @Param("limit") int limit);

  /**
   * Unmapped-pin summary for ONE SITE, split by the live window.
   *
   * <p>Returns {@code [devicePin, serialNumber, count, firstSeen, lastSeen, liveCount]} where
   * {@code liveCount} counts only punches received at or after {@code since} — the device claim, and
   * {@code serialNumber} is the terminal the pin was seen on MOST, carried for diagnostics only.
   *
   * <p>The split matters operationally: with backfill OFF, an archive-only pin is history and needs
   * no action, while a pin punching in the live window is somebody standing at the gate right now
   * whose punches are not being attributed. Collapsing the two into one list buries the actionable
   * few under hundreds of rows of archaeology.
   *
   * <p><b>Scoped to the site, and grouped by PIN alone.</b> Both were wrong before and both broke the
   * same screen. Without the site join this fed a {@code /sites/{siteId}/inbox} endpoint with every
   * site's punches, judged against one site's claim instant. And grouping by {@code (devicePin,
   * serialNumber)} while the view carries no serial meant one person appeared as N indistinguishable
   * rows — one per terminal they had walked past — with their punch counts split between them; Orion
   * Towers has an IN gate and an OUT gate, so on that fleet every unmapped pin double-rowed and the
   * archive tally counted pin-terminal pairs rather than people. An operator working that queue would
   * create the same person twice.
   *
   * <p>Note this necessarily excludes punches from UNCLAIMED terminals: those have no site, so they
   * cannot belong to a site's inbox. That signal lives on the Devices screen, which lists unadopted
   * terminals as its adoption queue — a better place for it than a per-pin work list.
   */
  @Query(
      value =
          """
          SELECT r."devicePin",
                 (array_agg(r."serialNumber" ORDER BY r."receivedAt" DESC))[1] AS serial,
                 count(*) AS n,
                 min(r."receivedAt") AS first_seen, max(r."receivedAt") AS last_seen,
                 count(*) FILTER (WHERE r."receivedAt" >= CAST(:since AS timestamptz)) AS live_n
            FROM "iclock_raw_punches" r
           WHERE r."serialNumber" IN (
                   SELECT d."serialNumber" FROM "iclock_devices" d WHERE d."siteId" = :siteId)
             AND NOT EXISTS (SELECT 1 FROM "iclock_punch_members" m WHERE m."rawPunchId" = r."id")
           GROUP BY r."devicePin"
           ORDER BY live_n DESC, n DESC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<Object[]> findUnmappedPinSummarySince(
      @Param("siteId") String siteId,
      @Param("since") java.time.Instant since,
      @Param("limit") int limit);

  long countBySerialNumberAndDeviceIdIsNull(String serialNumber);
}
