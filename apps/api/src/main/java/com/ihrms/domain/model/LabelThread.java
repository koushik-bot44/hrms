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
 * A per-user assignment of a {@link MailLabel} to a mail THREAD (table {@code label_threads}, §8). One row
 * per {@code labelId} + {@code threadId} means that label tags the conversation (row-exists = tagged;
 * removing a label deletes the row). Mirrors the {@code thread_stars}/{@code thread_archives} shape, keyed by
 * the label instead of the account — the label already carries the owner. Applying is idempotent (unique
 * pair); deleting the label cascades these away. The label is a tag overlay: it never moves or deletes the
 * thread.
 */
@Entity
@Table(name = "label_threads")
@Getter
@Setter
@NoArgsConstructor
public class LabelThread {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "labelId", nullable = false)
  private String labelId;

  @Column(name = "threadId", nullable = false)
  private String threadId;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  public LabelThread(String labelId, String threadId) {
    this.labelId = labelId;
    this.threadId = threadId;
  }
}
