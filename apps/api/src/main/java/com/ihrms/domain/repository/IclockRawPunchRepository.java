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
   *
   * <p><b>BOUNDED ON punchedAt AS WELL AS receivedAt</b>, and the second bound is the load-bearing one.
   *
   * <p>{@code receivedAt >= claimedAt} was the whole guard, and it assumes arrival time tracks punch
   * time. That holds for a running terminal and breaks completely for a newly adopted one: Building
   * No.9's four terminals dumped their entire memory AFTER being claimed, so 111,231 punches dated
   * back to 7 April arrived inside the "live" window. One operator click would have promoted ~104,673
   * of them and invented four months of attendance.
   *
   * <p>A ledger rule protects until somebody forgets. This is the bound, so no future click can do it
   * whatever anybody remembers. The grace hours cover the ordinary case the receivedAt guard was
   * written for — a genuine buffered flush, where punches predate their arrival by minutes to hours.
   */
  @Query(
      value =
          """
          SELECT r.* FROM "iclock_raw_punches" r
           WHERE r."receivedAt" >= CAST(:since AS timestamptz)
             AND (r."punchedAtRaw")::timestamp AT TIME ZONE 'Asia/Kolkata'
                 >= CAST(:since AS timestamptz) - make_interval(hours => CAST(:graceHours AS int))
             AND NOT EXISTS (SELECT 1 FROM "iclock_punch_members" m WHERE m."rawPunchId" = r."id")
           ORDER BY r."punchedAtRaw" ASC, r."id" ASC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<IclockRawPunch> findUnpromotedSince(
      @Param("since") java.time.Instant since,
      @Param("graceHours") int graceHours,
      @Param("limit") int limit);

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
           -- RECENCY FIRST. The pin that punched most recently is the person standing at the gate now;
           -- volume is context, not urgency, and remains a sortable column in the console. Live count
           -- breaks ties so an archive-only pin can never outrank one punching tonight.
           ORDER BY (max(r."receivedAt") >= CAST(:since AS timestamptz)) DESC,
                    max(r."receivedAt") DESC,
                    live_n DESC, n DESC
           LIMIT :limit
          """,
      nativeQuery = true)
  List<Object[]> findUnmappedPinSummarySince(
      @Param("siteId") String siteId,
      @Param("since") java.time.Instant since,
      @Param("limit") int limit);

  /**
   * THE PUNCH FEED: raw punches newest-first, with attribution resolved in the same query.
   *
   * <p>Returns {@code [id, receivedAt, punchedAtRaw, devicePin, serialNumber, deviceName, area,
   * direction, siteId, siteName, personId, personName, companyName, collapsedAway]}.
   *
   * <p><b>Raw, not effective.</b> This is the feed of what the terminals actually sent, so a punch that
   * burst-collapse absorbed still appears — that is the point of having it alongside the board. Whether
   * a row survived into an effective punch as the KEPT one, or was absorbed into somebody else's burst,
   * is computed here as {@code collapsedAway} rather than by a second round-trip per row.
   *
   * <p>Attribution is resolved by the SAME rule promotion uses — canonical pin within the device's site
   * — so a row reading "Unknown pin" here means the same thing it means in the inbox, rather than two
   * screens disagreeing about who somebody is. Devices with no site are excluded when a site filter is
   * supplied and included when it is not, which is what "All buildings" has to mean for a fleet view.
   *
   * <p>Ordered on {@code receivedAt} DESC — arrival order, which is what a live ticker is watching. The
   * device's own clock is shown beside it, because the two disagreeing IS the interesting case.
   */
  @Query(
      value =
          """
          SELECT r."id",
                 r."receivedAt",
                 r."punchedAtRaw",
                 r."devicePin",
                 r."serialNumber",
                 d."name"        AS device_name,
                 d."area"        AS device_area,
                 d."direction"   AS device_direction,
                 d."siteId"      AS site_id,
                 s."name"        AS site_name,
                 p."id"          AS person_id,
                 p."name"        AS person_name,
                 c."name"        AS company_name,
                 r."verifyMode"  AS verify_mode,
                 EXISTS (SELECT 1 FROM "iclock_punch_members" m
                          WHERE m."rawPunchId" = r."id"
                            AND NOT EXISTS (SELECT 1 FROM "iclock_punches" q
                                             WHERE q."id" = m."punchId"
                                               AND q."effectiveRawPunchId" = r."id")) AS collapsed_away
            FROM "iclock_raw_punches" r
            LEFT JOIN "iclock_devices" d ON d."serialNumber" = r."serialNumber"
            LEFT JOIN "iclock_sites"   s ON s."id" = d."siteId"
            LEFT JOIN "iclock_people"  p ON p."siteId" = d."siteId"
                                        AND p."pin" = regexp_replace(r."devicePin", '^0+', '')
            LEFT JOIN "companies"      c ON c."id" = p."companyId"
           WHERE (:siteId IS NULL OR d."siteId" = :siteId)
             AND (:deviceId IS NULL OR d."id" = :deviceId)
             AND (CAST(:from AS timestamptz) IS NULL OR r."receivedAt" >= CAST(:from AS timestamptz))
             AND (CAST(:to   AS timestamptz) IS NULL OR r."receivedAt" <  CAST(:to   AS timestamptz))
             AND (:unknownOnly = FALSE OR p."id" IS NULL)
             AND (:rosteredOnly = FALSE OR p."id" IS NOT NULL)
           ORDER BY r."receivedAt" DESC, r."id" DESC
           LIMIT :limit OFFSET :offset
          """,
      nativeQuery = true)
  List<Object[]> findFeed(
      @Param("siteId") String siteId,
      @Param("deviceId") String deviceId,
      @Param("from") java.time.Instant from,
      @Param("to") java.time.Instant to,
      @Param("unknownOnly") boolean unknownOnly,
      @Param("rosteredOnly") boolean rosteredOnly,
      @Param("limit") int limit,
      @Param("offset") int offset);

  /**
   * How one pin has actually been passing, by verify mode.
   *
   * <p>Reads the RAW table rather than the promoted one, because a burst-collapsed punch was still a
   * real presentation of a face or a finger — the question here is which credential the person uses,
   * not which row survived de-duplication.
   */
  @Query(
      value =
          """
          SELECT r."verifyMode", count(*)
            FROM "iclock_raw_punches" r
            LEFT JOIN "iclock_devices" d ON d."id" = r."deviceId"
           WHERE regexp_replace(r."devicePin", '^0+', '') = :pin
             AND (:siteId IS NULL OR d."siteId" = :siteId)
           GROUP BY r."verifyMode"
          """,
      nativeQuery = true)
  List<Object[]> countByVerifyModeForPin(@Param("pin") String pin, @Param("siteId") String siteId);

  /** Total matching the same filters, so the feed can paginate honestly rather than guessing. */
  @Query(
      value =
          """
          SELECT count(*)
            FROM "iclock_raw_punches" r
            LEFT JOIN "iclock_devices" d ON d."serialNumber" = r."serialNumber"
            LEFT JOIN "iclock_people"  p ON p."siteId" = d."siteId"
                                        AND p."pin" = regexp_replace(r."devicePin", '^0+', '')
           WHERE (:siteId IS NULL OR d."siteId" = :siteId)
             AND (:deviceId IS NULL OR d."id" = :deviceId)
             AND (CAST(:from AS timestamptz) IS NULL OR r."receivedAt" >= CAST(:from AS timestamptz))
             AND (CAST(:to   AS timestamptz) IS NULL OR r."receivedAt" <  CAST(:to   AS timestamptz))
             AND (:unknownOnly = FALSE OR p."id" IS NULL)
             AND (:rosteredOnly = FALSE OR p."id" IS NOT NULL)
          """,
      nativeQuery = true)
  long countFeed(
      @Param("siteId") String siteId,
      @Param("deviceId") String deviceId,
      @Param("from") java.time.Instant from,
      @Param("to") java.time.Instant to,
      @Param("unknownOnly") boolean unknownOnly,
      @Param("rosteredOnly") boolean rosteredOnly);

  long countBySerialNumberAndDeviceIdIsNull(String serialNumber);
}
