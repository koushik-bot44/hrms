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
 * An internal mail message (table {@code messages}, §8). Internal-only — a DB row, never external
 * email. Text body in v1; fans out to one or more {@link MessageRecipient} rows.
 */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class Message {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** The conversation this message belongs to (§8). A new compose mints one; replies reuse it. */
  @Column(name = "threadId", nullable = false)
  private String threadId;

  /** The message this one replies to (the reply chain); null for a thread's first message. */
  @Column(name = "parentMessageId")
  private String parentMessageId;

  /** The sender when it is a staff account; null when the sender is an employee (§8, Stage 5). */
  @Column(name = "senderUserId")
  private String senderUserId;

  /** The sender when it is an employee; null when the sender is staff. Exactly one sender column is set. */
  @Column(name = "senderEmployeeId")
  private String senderEmployeeId;

  @Column(name = "subject", nullable = false)
  private String subject;

  @Column(name = "body", nullable = false)
  private String body;

  /** The sender's per-user soft-hide (§8): set when the sender deletes; the row is never destroyed. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "senderDeletedAt")
  private Instant senderDeletedAt;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  /** The sender's account id whichever kind it is (user or employee) — ids are globally unique (§8). */
  public String senderAccountId() {
    return senderUserId != null ? senderUserId : senderEmployeeId;
  }
}
