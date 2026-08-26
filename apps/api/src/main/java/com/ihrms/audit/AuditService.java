package com.ihrms.audit;

import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.repository.AuditLogRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Writes append-only AuditLog rows (§7). Failures are logged but never propagated — a
 * transient audit error must not fail the user's action. Used by the mutation interceptor
 * and (in later phases) by handlers for explicit sensitive-read writes (e.g. document view).
 *
 * <p><b>Refuses to run inside a read-only transaction.</b> There the write is accepted and then
 * discarded at commit by {@code FlushMode.MANUAL} — no exception, nothing for the catch below to log,
 * and an audit gap that reads exactly like "nothing happened". Better to fail loudly in the log than
 * to hand back a trail with holes in it.
 */
@Service
public class AuditService {

  private static final Logger log = LoggerFactory.getLogger(AuditService.class);

  private final AuditLogRepository repository;

  public AuditService(AuditLogRepository repository) {
    this.repository = repository;
  }

  public void record(
      AuditActor actor,
      String action,
      String targetType,
      String targetId,
      Map<String, Object> metadata,
      String ipAddress) {
    // A read-only transaction puts Hibernate in FlushMode.MANUAL, so this save() is accepted, never
    // flushed, and silently discarded at commit — no exception, and the catch below would have nothing
    // to log. An audit trail that quietly loses entries is worse than one that fails loudly, because
    // the gap is indistinguishable from "nothing happened". Callers must audit at the controller or
    // scheduler layer, outside the read-only boundary, which is what IclockAdminController does.
    if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
      log.error(
          "REFUSING to record audit \"{}\": called inside a read-only transaction, where the write "
              + "would be silently dropped. Audit at the controller or scheduler layer instead.",
          action);
      return;
    }
    try {
      AuditLog row = new AuditLog();
      row.setCompanyId(actor.companyId());
      row.setActorType(actor.actorType());
      row.setActorId(actor.actorId());
      row.setAction(action);
      row.setTargetType(targetType);
      row.setTargetId(targetId);
      row.setMetadata(metadata);
      row.setIpAddress(ipAddress);
      repository.save(row);
    } catch (RuntimeException e) {
      log.error("Failed to write audit log for \"{}\"", action, e);
    }
  }
}
