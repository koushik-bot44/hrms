package com.ihrms.domain.repository;

import com.ihrms.domain.model.PushSubscription;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Web Push subscriptions (§ Web Push). Subscribe upserts on the unique {@code endpoint}; fan-out loads a
 * principal's subscriptions by {@code userId} / {@code employeeId}; a stale endpoint (410 from the push
 * service) is pruned by endpoint.
 */
public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, String> {

  Optional<PushSubscription> findByEndpoint(String endpoint);

  List<PushSubscription> findByUserId(String userId);

  List<PushSubscription> findByEmployeeId(String employeeId);

  /**
   * One-off stale cleanup (§ Web Push): the subscriptions created before {@code cutoff}. Origin is not
   * stored (the endpoint is a push-service host), so pre-migration (e.g. old-origin) subscriptions are
   * identified by age; genuinely-dead endpoints are pruned automatically on the next 410/404 send. Loaded as
   * entities (not a derived bulk delete) so the caller removes them via {@code deleteAll} in the same
   * transaction as its audit write — a bulk JPQL delete mixed with a same-tx entity insert forces a spurious
   * post-flush UPDATE that the append-only {@code audit_logs} trigger rejects.
   */
  List<PushSubscription> findByCreatedAtBefore(Instant cutoff);
}
