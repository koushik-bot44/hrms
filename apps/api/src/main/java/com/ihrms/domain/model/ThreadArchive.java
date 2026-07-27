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
 * A per-user ARCHIVE on a mail THREAD (table {@code thread_archives}, §8). One row per {@code threadId} +
 * {@code accountId} means that account has archived the conversation (row-exists = archived; unarchive
 * deletes the row). Mirrors {@link ThreadStar} in shape, but with one behavioural difference: an archived
 * thread is HIDDEN FROM THAT USER'S INBOX (the inbox query excludes archived threads) — it is not deleted and
 * still shows in the Archive view, in Sent/Search/Starred, and normally for other participants. {@code
 * accountId} is the archiver's globally-unique account id (a User id or an Employee id — the same id the mail
 * service resolves via {@code senderAccountId}/{@code recipientAccountId}). Archive is per-user and
 * independent of read/star/deleted; a new inbound message clears it (Gmail-style resurface to Inbox).
 */
@Entity
@Table(name = "thread_archives")
@Getter
@Setter
@NoArgsConstructor
public class ThreadArchive {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "threadId", nullable = false)
  private String threadId;

  @Column(name = "accountId", nullable = false)
  private String accountId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  public ThreadArchive(String threadId, String accountId) {
    this.threadId = threadId;
    this.accountId = accountId;
  }
}
