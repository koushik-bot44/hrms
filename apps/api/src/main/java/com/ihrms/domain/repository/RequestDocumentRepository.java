package com.ihrms.domain.repository;

import com.ihrms.domain.model.RequestDocument;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Fulfilment files for a {@link com.ihrms.domain.model.DocumentRequest} (§8d). A bound (fulfilled) file has
 * a non-null {@code sha256}; unbound drafts (upload-url issued, never resolved) do not and are hidden from
 * the employee's view + downloads.
 */
public interface RequestDocumentRepository extends JpaRepository<RequestDocument, String> {

  List<RequestDocument> findByRequestIdOrderByCreatedAtAsc(String requestId);

  /** The fulfilled files for a set of requests (batched list view) — bound only. */
  List<RequestDocument> findByRequestIdInAndSha256IsNotNullOrderByCreatedAtAsc(
      List<String> requestIds);
}
