package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A fulfilment file the Accountant uploads to a {@link DocumentRequest} (table {@code request_documents},
 * §8d). Created as an unbound DRAFT at upload-url time (NULL {@code sha256}); {@code resolve} re-reads the
 * bytes, re-validates, and sets the real size + {@code sha256} — a bound (sha256 != null) row is a
 * fulfilled document. The raw {@code storageKey} is never exposed; files are reached only via presigned URLs.
 */
@Entity
@Table(name = "request_documents")
@Getter
@Setter
@NoArgsConstructor
public class RequestDocument {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "requestId", nullable = false)
  private String requestId;

  @Column(name = "fileName", nullable = false)
  private String fileName;

  @Column(name = "contentType", nullable = false)
  private String contentType;

  @Column(name = "sizeBytes", nullable = false)
  private long sizeBytes;

  @Column(name = "storageKey", nullable = false)
  private String storageKey;

  /** NULL while an unbound draft; set to the bytes' hash once the request is resolved (= fulfilled). */
  @Column(name = "sha256")
  private String sha256;

  @Column(name = "uploadedByUserId", nullable = false)
  private String uploadedByUserId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  /** A bound (fulfilled) document has its hash computed; unbound drafts do not. */
  public boolean isBound() {
    return sha256 != null;
  }
}
