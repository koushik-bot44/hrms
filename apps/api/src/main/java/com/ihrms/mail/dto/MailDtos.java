package com.ihrms.mail.dto;

import com.ihrms.domain.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Internal-mail request/response DTOs (§8). Mail is organised into <b>threads</b> (conversations):
 * Inbox / Sent / Search list threads, and a thread opens to its messages in order. Addresses are logical
 * {@code localpart@domain} display handles; routing/authorization is by role + companyId, never by
 * parsing the address. springdoc-visible so the web types regenerate.
 */
public final class MailDtos {

  private MailDtos() {}

  /** A mail participant — a staff account, shown by name + address. */
  public record MailPartyView(String userId, String name, String address, UserRole role) {}

  /** Compose a NEW thread. The recipient is chosen from {@code /mail/contacts}; the graph is re-checked. */
  public record SendMessageRequest(
      @NotBlank(message = "Recipient is required") String toUserId,
      @NotBlank(message = "Subject is required") @Size(max = 200, message = "Subject is too long")
          String subject,
      @NotBlank(message = "Message body is required") @Size(max = 10000, message = "Message is too long")
          String body) {}

  /** Reply within a thread — the recipient is derived from the thread, then re-checked by the graph. */
  public record ReplyRequest(
      @NotBlank(message = "Message body is required") @Size(max = 10000, message = "Message is too long")
          String body) {}

  /** Identifies the created message + the thread it lives in (so the client can open the thread). */
  public record SendMessageResult(String id, String threadId) {}

  /**
   * One row in an Inbox / Sent / Search list — a THREAD from the viewer's perspective: the other
   * participant(s), subject, a snippet, the latest activity time, how many messages, and whether any is
   * unread for the viewer.
   */
  public record ThreadListItemView(
      String threadId,
      String subject,
      String snippet,
      String lastMessageAt,
      List<MailPartyView> participants,
      int messageCount,
      boolean unread) {}

  public record ThreadPage(
      List<ThreadListItemView> content, int page, int size, long totalElements, int totalPages) {}

  /** One message inside an open thread. {@code mine} marks the viewer's own messages. */
  public record ThreadMessageView(
      String id, MailPartyView from, String body, String createdAt, boolean mine) {}

  /**
   * An open thread: its messages in chronological order + the {@code counterparty} a reply would go to
   * (derived from the thread, still graph-checked on send).
   */
  public record ThreadDetailView(
      String threadId,
      String subject,
      List<MailPartyView> participants,
      MailPartyView counterparty,
      List<ThreadMessageView> messages) {}

  public record UnreadCountView(long unread) {}
}
