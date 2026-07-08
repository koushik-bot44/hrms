package com.ihrms.mail.dto;

import com.ihrms.domain.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Internal-mail request/response DTOs (§8). Addresses are logical {@code localpart@domain} display
 * handles; routing/authorization is by role + companyId, never by parsing the address. springdoc-visible
 * so the web types regenerate.
 */
public final class MailDtos {

  private MailDtos() {}

  /** A mail participant — a staff account, shown by name + address. */
  public record MailPartyView(String userId, String name, String address, UserRole role) {}

  /** Compose form. The recipient is chosen from {@code /mail/contacts}; the graph is re-checked on send. */
  public record SendMessageRequest(
      @NotBlank(message = "Recipient is required") String toUserId,
      @NotBlank(message = "Subject is required") @Size(max = 200, message = "Subject is too long")
          String subject,
      @NotBlank(message = "Message body is required") @Size(max = 10000, message = "Message is too long")
          String body) {}

  public record SendMessageResult(String id) {}

  /** An inbox row — who it is from + this recipient's read state. */
  public record InboxMessageView(
      String id, String subject, String createdAt, MailPartyView from, boolean read) {}

  /** A sent row — who it went to. */
  public record SentMessageView(
      String id, String subject, String createdAt, List<MailPartyView> to) {}

  /** Full message: whether the viewer may read it is enforced server-side (sender or a recipient). */
  public record MessageView(
      String id,
      String subject,
      String body,
      String createdAt,
      MailPartyView from,
      List<MailPartyView> to,
      boolean read) {}

  public record InboxPage(
      List<InboxMessageView> content, int page, int size, long totalElements, int totalPages) {}

  public record SentPage(
      List<SentMessageView> content, int page, int size, long totalElements, int totalPages) {}

  public record UnreadCountView(long unread) {}
}
