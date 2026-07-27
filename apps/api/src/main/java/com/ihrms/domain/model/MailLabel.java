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
 * An author-private mail LABEL (table {@code mail_labels}, §8) — a user-created named tag for organizing
 * conversations. Owned by one account ({@code authorAccountId}); a name is unique per author,
 * case-insensitively. A label tags whole threads via {@link LabelThread} rows (many-to-many); applying one
 * is a tag overlay, never a move. Only the author can see/use their labels.
 */
@Entity
@Table(name = "mail_labels")
@Getter
@Setter
@NoArgsConstructor
public class MailLabel {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  /** The label owner — a globally-unique account id (a User id or an Employee id). */
  @Column(name = "authorAccountId", nullable = false)
  private String authorAccountId;

  @Column(name = "name", nullable = false)
  private String name;

  @CreationTimestamp
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "createdAt", nullable = false, updatable = false)
  private Instant createdAt;

  public MailLabel(String authorAccountId, String name) {
    this.authorAccountId = authorAccountId;
    this.name = name;
  }
}
