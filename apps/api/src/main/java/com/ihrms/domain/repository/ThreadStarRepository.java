package com.ihrms.domain.repository;

import com.ihrms.domain.model.ThreadStar;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Per-user thread stars (§8). Keyed by the star owner's globally-unique {@code accountId}; row-exists =
 * starred.
 */
public interface ThreadStarRepository extends JpaRepository<ThreadStar, String> {

  boolean existsByThreadIdAndAccountId(String threadId, String accountId);

  Optional<ThreadStar> findByThreadIdAndAccountId(String threadId, String accountId);

  /** The viewer's stars among a page of threads (for the per-row {@code starred} flag). */
  List<ThreadStar> findByAccountIdAndThreadIdIn(String accountId, Collection<String> threadIds);
}
