package com.ihrms.audit;

import com.ihrms.domain.model.AuditLog;
import com.ihrms.domain.repository.AuditLogRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Writes append-only AuditLog rows (§7). Failures are logged but never propagated — a
 * transient audit error must not fail the user's action. Used by the mutation interceptor
 * and (in later phases) by handlers for explicit sensitive-read writes (e.g. document view).
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
