package com.ihrms.domain.repository;

import com.ihrms.domain.model.IclockRequestLog;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IclockRequestLogRepository extends JpaRepository<IclockRequestLog, String> {

  /** Per-device request history, newest first — used to read the firmware dialect off real traffic. */
  List<IclockRequestLog> findBySerialNumberOrderByReceivedAtDesc(String serialNumber);

  /**
   * Captured pushes for one registry, oldest first — the source the one-off replay re-parses. Paged
   * because a body can be several KB and a backlog upload runs to thousands of rows.
   */
  Page<IclockRequestLog> findByTableNameIgnoreCaseOrderByReceivedAtAsc(
      String tableName, Pageable pageable);

  /**
   * Best-effort completion of a row that was already committed before the handler ran. A targeted
   * UPDATE rather than a load-modify-save so it cannot resurrect a stale entity or touch any other
   * column. Legal here because — unlike {@code audit_logs} — this table carries no append-only
   * trigger.
   */
  @Modifying
  @Query(
      "update IclockRequestLog l set l.responseStatus = :status, l.durationMs = :durationMs"
          + " where l.id = :id")
  int completeRequest(
      @Param("id") String id,
      @Param("status") Integer status,
      @Param("durationMs") Integer durationMs);
}
