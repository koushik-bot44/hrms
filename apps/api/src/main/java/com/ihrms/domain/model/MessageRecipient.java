package com.ihrms.domain.model;

import com.ihrms.domain.enums.RecipientType;
import com.ihrms.domain.support.CuidId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  /** The recipient when it is a staff account; null when the recipient is an employee (§8, Stage 5). */
  @Column(name = "recipientUserId")
  private String recipientUserId;

  /** The recipient when it is an employee; null when staff. Exactly one recipient column is set. */
  @Column(name = "recipientEmployeeId")
  private String recipientEmployeeId;

  /** How this recipient was addressed (§8): TO / CC / BCC. BCC is hidden from other recipients. */
  @Enumerated(EnumType.STRING)
  @JdbcTypeCode(SqlTypes.NAMED_ENUM)
  @Column(name = "recipientType", nullable = false)
  private RecipientType recipientType = RecipientType.TO;

  /** When the recipient first opened the message; null while unread. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "readAt")
  private Instant readAt;

  /** The recipient's per-user soft-hide (§8): set when they delete; the row is never destroyed. */
  @JdbcTypeCode(SqlTypes.TIMESTAMP)
  @Column(name = "deletedAt")
  private Instant deletedAt;

  /** The recipient's account id whichever kind it is (user or employee) — ids are globally unique (§8). */
  public String recipientAccountId() {
    return recipientUserId != null ? recipientUserId : recipientEmployeeId;
  }
}
