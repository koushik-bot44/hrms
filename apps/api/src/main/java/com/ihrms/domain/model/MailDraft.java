package com.ihrms.domain.model;

import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

/**
 * An author-private, UNSENT mail composition (table {@code mail_drafts}, §8). A draft is deliberately NOT a
 * {@link Message}: it has no delivery rows, no thread, no inbox presence, and fires no notification. It
 * simply remembers what the author has entered so far — recipient id lists (which may be empty or point at
 * accounts that aren't currently permitted), subject/body (which may be empty), the ids of the author's
 * unbound {@link MessageAttachment} uploads, and — for a reply-draft — the {@code replyToThreadId} it would
 * reply into. Save is permissive; {@code canSendMail} + the normal validation run only when the draft is
 * SENT, which reconstructs the real compose/reply request, delivers it, and then deletes the draft.
 */
@Entity
@Table(name = "mail_drafts")
@Getter
@Setter
@NoArgsConstructor
public class MailDraft {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** The draft owner — a globally-unique account id (a User id or an Employee id). Author-scoped access. */
  @Column(name = "authorAccountId", nullable = false)
  private String authorAccountId;

  @Column(name = "subject")
  private String subject;

  @Column(name = "body")
  private String body;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "toIds")
  private List<String> toIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "ccIds")
  private List<String> ccIds;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "bccIds")
  private List<String> bccIds;

  /** Ids of the author's unbound {@code message_attachments} draft uploads (reused compose handshake). */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "attachmentIds")
  private List<String> attachmentIds;

  /** Set => this is a reply-draft; sending routes through the reply path (recipients are re-derived). */
  @Column(name = "replyToThreadId")
  private String replyToThreadId;

  @Column(name = "replyAll", nullable = false)
  private boolean replyAll;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  @UpdateTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "updatedAt", nullable = false)
  private Instant updatedAt;

  public MailDraft(String authorAccountId) {
    this.authorAccountId = authorAccountId;
  }
}
