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
 * A file carried by a mail {@link Message} (table {@code message_attachments}, §8). Stored in S3 via the
 * presigned upload→confirm handshake; the raw {@code storageKey} is never exposed to clients — downloads
 * go through a short-lived, participant-scoped presigned GET. {@code messageId} is null while the
 * attachment is a DRAFT (uploaded but not yet bound to a sent message).
 */
@Entity
@Table(name = "message_attachments")
@Getter
@Setter
@NoArgsConstructor
public class MessageAttachment {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** The message this attachment belongs to; null while it is an unbound draft. */
  @Column(name = "messageId")
  private String messageId;

  /** The staff account that uploaded it (only they may bind their own drafts). */
  @Column(name = "uploaderUserId", nullable = false)
  private String uploaderUserId;

  @Column(name = "fileName", nullable = false)
  private String fileName;

  @Column(name = "contentType", nullable = false)
  private String contentType;

  @Column(name = "sizeBytes", nullable = false)
  private long sizeBytes;

  /** The opaque storage object key — never returned to clients (§6). */
  @Column(name = "storageKey", nullable = false)
  private String storageKey;

  /** Hex SHA-256 of the stored bytes, computed on bind (integrity, §4); null until bound. */
  @Column(name = "sha256")
  private String sha256;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;
}
