package com.ihrms.domain.repository;

import com.ihrms.domain.model.ThreadArchive;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Per-user thread archives (§8). Keyed by the archiver's globally-unique {@code accountId}; row-exists =
 * archived. Mirrors {@code ThreadStarRepository}, plus a bulk delete used to un-archive a thread for its
 * recipients when a new message is delivered (the Gmail-style resurface-to-Inbox).
 */
public interface ThreadArchiveRepository extends JpaRepository<ThreadArchive, String> {

  boolean existsByThreadIdAndAccountId(String threadId, String accountId);

  Optional<ThreadArchive> findByThreadIdAndAccountId(String threadId, String accountId);

  /** The viewer's archives among a page of threads (for the per-row {@code archived} flag). */
  List<ThreadArchive> findByAccountIdAndThreadIdIn(String accountId, Collection<String> threadIds);

  /**
   * Clear the archive for a set of accounts on one thread — used on delivery so a new inbound message
   * resurfaces the thread to each recipient's Inbox. A no-op for accounts that hadn't archived it.
   */
  void deleteByThreadIdAndAccountIdIn(String threadId, Collection<String> accountIds);
}
