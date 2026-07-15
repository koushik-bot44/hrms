package com.ihrms.mail.dto;

import com.ihrms.domain.enums.UserRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

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

  /**
   * Compose a NEW thread with one or more recipients (§8). At least one TO; CC/BCC optional. Recipients are
   * chosen from {@code /mail/contacts}; EVERY recipient across TO+CC+BCC is re-checked by the graph.
   */
  public record SendMessageRequest(
      @NotEmpty(message = "At least one recipient is required") List<String> toUserIds,
      List<String> ccUserIds,
      List<String> bccUserIds,
      @NotBlank(message = "Subject is required") @Size(max = 200, message = "Subject is too long")
          String subject,
      @NotBlank(message = "Message body is required") @Size(max = 10000, message = "Message is too long")
          String body,
      // Ids of already-uploaded attachment drafts to bind to this message (§8); at most 5.
      @Size(max = 5, message = "At most 5 attachments per message") List<String> attachmentIds) {}

  /** Reply within a thread — the recipient is derived from the thread, then re-checked by the graph. */
  public record ReplyRequest(
      @NotBlank(message = "Message body is required") @Size(max = 10000, message = "Message is too long")
          String body,
      @Size(max = 5, message = "At most 5 attachments per message") List<String> attachmentIds) {}

  /** Identifies the created message + the thread it lives in (so the client can open the thread). */
  public record SendMessageResult(String id, String threadId) {}

  /** Request a presigned upload for one attachment (validated server-side by type + extension + size). */
  public record AttachmentUploadRequest(
      @NotBlank(message = "A file name is required") @Size(max = 255, message = "File name is too long")
          String fileName,
      @NotBlank(message = "A content type is required") String contentType,
      @Positive(message = "sizeBytes must be positive") long sizeBytes) {}

  /** The presigned PUT + the draft attachment id to send once the upload completes. */
  public record AttachmentUpload(
      String attachmentId,
      String uploadUrl,
      String method,
      Map<String, String> headers,
      int expiresInSeconds) {}

  /** Attachment metadata shown on a message — never the storage key (§6). */
  public record AttachmentView(String id, String fileName, String contentType, long sizeBytes) {}

  /** A short-lived, participant-scoped presigned GET for one attachment. */
  public record AttachmentDownload(String url, int expiresInSeconds) {}

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
      boolean unread,
      boolean hasAttachments) {}

  public record ThreadPage(
      List<ThreadListItemView> content, int page, int size, long totalElements, int totalPages) {}

  /**
   * One message inside an open thread. {@code mine} marks the viewer's own messages. {@code to} / {@code
   * cc} are all TO/CC recipients; {@code bcc} is the BCC recipients VISIBLE to this viewer only (the
   * sender sees all BCC; a BCC recipient sees only themselves; everyone else sees none — computed
   * server-side, never leaked).
   */
  public record ThreadMessageView(
      String id,
      MailPartyView from,
      List<MailPartyView> to,
      List<MailPartyView> cc,
      List<MailPartyView> bcc,
      String body,
      String createdAt,
      boolean mine,
      List<AttachmentView> attachments) {}

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
