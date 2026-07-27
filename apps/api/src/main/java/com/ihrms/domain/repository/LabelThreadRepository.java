package com.ihrms.domain.repository;

import com.ihrms.domain.model.LabelThread;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user label→thread assignments (§8). Row-exists = tagged. Author scoping is via the label (the caller
 * only ever passes labels they own); applying is idempotent (unique {@code (labelId, threadId)} pair).
 */
public interface LabelThreadRepository extends JpaRepository<LabelThread, String> {

  boolean existsByLabelIdAndThreadId(String labelId, String threadId);

  @Transactional
  void deleteByLabelIdAndThreadId(String labelId, String threadId);

  long countByLabelId(String labelId);

  /** The assignments among a page of threads for a set of the author's labels (batched chip lookup). */
  List<LabelThread> findByLabelIdInAndThreadIdIn(
      Collection<String> labelIds, Collection<String> threadIds);
}
