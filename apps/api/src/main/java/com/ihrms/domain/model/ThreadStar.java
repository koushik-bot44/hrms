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
 * A per-user star on a mail THREAD (table {@code thread_stars}, §8). One row per {@code threadId} +
 * {@code accountId} means that account has starred the conversation (row-exists = starred; unstar deletes
 * the row). {@code accountId} is the star owner's globally-unique account id (a User id or an Employee id
 * — the same id the mail service resolves via {@code senderAccountId}/{@code recipientAccountId}). Starring
 * is per-user and independent of read/deleted — it never moves or deletes the thread. The first of the
 * per-user-thread states (Archive + Labels reuse the shape).
 */
@Entity
@Table(name = "thread_stars")
@Getter
@Setter
@NoArgsConstructor
public class ThreadStar {

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

  public ThreadStar(String threadId, String accountId) {
    this.threadId = threadId;
    this.accountId = accountId;
  }
}
