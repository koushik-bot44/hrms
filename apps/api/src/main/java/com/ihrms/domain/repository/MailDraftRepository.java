package com.ihrms.domain.repository;

import com.ihrms.domain.model.MailDraft;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Author-private mail drafts (§8). Every read/edit/discard/send is scoped to the owning {@code
 * authorAccountId} — a draft is never visible to anyone else (a missing/foreign id is a 404).
 */
public interface MailDraftRepository extends JpaRepository<MailDraft, String> {

  /** The author's drafts, most-recently-edited first (the Drafts view). */
  Page<MailDraft> findByAuthorAccountIdOrderByUpdatedAtDesc(String authorAccountId, Pageable pageable);

  /** Load one of the author's OWN drafts (author-scoped — a foreign/missing id resolves empty => 404). */
  Optional<MailDraft> findByIdAndAuthorAccountId(String id, String authorAccountId);
}
