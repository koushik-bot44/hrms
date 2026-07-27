package com.ihrms.domain.repository;

import com.ihrms.domain.model.MailLabel;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Author-private mail labels (§8). Every read/rename/delete/apply is scoped to the owning {@code
 * authorAccountId} — a label is never visible to anyone else (a missing/foreign id is a 404). Names are
 * unique per author, case-insensitively.
 */
public interface MailLabelRepository extends JpaRepository<MailLabel, String> {

  /** The author's labels, alphabetical (the rail list). */
  List<MailLabel> findByAuthorAccountIdOrderByNameAsc(String authorAccountId);

  /** Load one of the author's OWN labels (author-scoped — a foreign/missing id resolves empty => 404). */
  Optional<MailLabel> findByIdAndAuthorAccountId(String id, String authorAccountId);

  /** Dup-name guard for CREATE (case-insensitive, per author). */
  boolean existsByAuthorAccountIdAndNameIgnoreCase(String authorAccountId, String name);

  /** Dup-name guard for RENAME — excludes the label being renamed. */
  boolean existsByAuthorAccountIdAndNameIgnoreCaseAndIdNot(
      String authorAccountId, String name, String id);
}
