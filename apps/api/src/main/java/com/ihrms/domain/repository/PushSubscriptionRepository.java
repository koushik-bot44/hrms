package com.ihrms.domain.repository;

import com.ihrms.domain.model.PushSubscription;
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
}
