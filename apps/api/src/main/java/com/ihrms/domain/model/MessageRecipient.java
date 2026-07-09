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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One recipient of a {@link Message} (table {@code message_recipients}, §8). Carries the per-recipient
 * read state ({@code readAt}, null until the recipient opens it). A child table so a message can fan
 * out to several recipients later.
 */
@Entity
@Table(name = "message_recipients")
@Getter
@Setter
@NoArgsConstructor
public class MessageRecipient {

  @Id
  @CuidId
  @Column(name = "id")
  private String id;

  @Column(name = "messageId", nullable = false)
  private String messageId;

  @Column(name = "recipientUserId", nullable = false)
  private String recipientUserId;

  /** When the recipient first opened the message; null while unread. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "readAt")
  private Instant readAt;

  /** The recipient's per-user soft-hide (§8): set when they delete; the row is never destroyed. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deletedAt")
  private Instant deletedAt;
}
