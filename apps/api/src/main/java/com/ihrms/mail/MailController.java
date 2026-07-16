package com.ihrms.mail;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.mail.dto.MailDtos.AttachmentDownload;
import com.ihrms.mail.dto.MailDtos.AttachmentUpload;
import com.ihrms.mail.dto.MailDtos.AttachmentUploadRequest;
import com.ihrms.mail.dto.MailDtos.MailPartyView;
import com.ihrms.mail.dto.MailDtos.ReplyRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageResult;
import com.ihrms.mail.dto.MailDtos.ThreadDetailView;
import com.ihrms.mail.dto.MailDtos.ThreadPage;
import com.ihrms.mail.dto.MailDtos.UnreadCountView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal mailbox (ARCHITECTURE.md §8) — thread-based (Stage 3). Every handler acts as the authenticated
 * staff caller on their OWN mailbox — no role gate here (the SecurityConfig rule keeps employees out; the
 * send graph is the real gate, enforced in the service and surfaced as 403). Never role-annotate this
 * controller: {@code @PreAuthorize} denials would surface as 500, not 403.
 */
@RestController
@RequestMapping("/mail")
public class MailController {

  private final InternalMailService mail;
  private final MailPushNotifier pushNotifier;

  public MailController(InternalMailService mail, MailPushNotifier pushNotifier) {
    this.mail = mail;
    this.pushNotifier = pushNotifier;
  }

  /** The accounts the caller may message (the send graph as a concrete list). */
  @GetMapping("/contacts")
  public List<MailPartyView> contacts(@AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.contacts(actor);
  }

  /** Start a new thread to one recipient. 403 if the send graph forbids it. */
  @PostMapping("/messages")
  public SendMessageResult send(
      @Valid @RequestBody SendMessageRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    SendMessageResult result = mail.send(actor, body, request.getRemoteAddr());
    // Post-commit OS push to every recipient except the sender (§ Web Push N3; best-effort).
    pushNotifier.notifyRecipients(result.id());
    return result;
  }

  /** Reply within a thread — to the original SENDER only; still graph-checked (403 if forbidden). */
  @PostMapping("/threads/{id}/reply")
  public SendMessageResult reply(
      @PathVariable String id,
      @Valid @RequestBody ReplyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    SendMessageResult result = mail.reply(actor, id, body, request.getRemoteAddr());
    pushNotifier.notifyRecipients(result.id()); // post-commit, best-effort (§ Web Push N3)
    return result;
  }

  /** Reply ALL — sender + original TO + CC (never BCC, never the actor); each graph-checked. */
  @PostMapping("/threads/{id}/reply-all")
  public SendMessageResult replyAll(
      @PathVariable String id,
      @Valid @RequestBody ReplyRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    SendMessageResult result = mail.replyAll(actor, id, body, request.getRemoteAddr());
    pushNotifier.notifyRecipients(result.id()); // post-commit, best-effort (§ Web Push N3)
    return result;
  }

  /** Step 1 of attaching a file: validate + get a short-lived presigned PUT (+ a draft attachment id). */
  @PostMapping("/attachments/upload-url")
  public AttachmentUpload attachmentUploadUrl(
      @Valid @RequestBody AttachmentUploadRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.requestAttachmentUpload(actor, body, request.getRemoteAddr());
  }

  /** Download an attachment — participant-scoped presigned GET (403 for non-participants). Audited. */
  @GetMapping("/attachments/{id}/download")
  public AttachmentDownload attachmentDownload(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.downloadAttachment(actor, id, request.getRemoteAddr());
  }

  /** Inbox — conversations addressed to the caller, newest activity first. */
  @GetMapping("/inbox")
  public ThreadPage inbox(
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.inbox(actor, pageable);
  }

  /** Sent — conversations the caller has sent into. */
  @GetMapping("/sent")
  public ThreadPage sent(
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.sent(actor, pageable);
  }

  /** Search the caller's own mail (subject + body, case-insensitive). */
  @GetMapping("/search")
  public ThreadPage search(
      @RequestParam(name = "q", required = false) String q,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.search(actor, q, pageable);
  }

  /** Open one thread — participant only (else 403). Marks it read. */
  @GetMapping("/threads/{id}")
  public ThreadDetailView thread(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.thread(actor, id, request.getRemoteAddr());
  }

  /** Mark a thread read for the caller; returns the refreshed unread count. */
  @PostMapping("/threads/{id}/read")
  public UnreadCountView markRead(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.markRead(actor, id);
  }

  /** Mark a thread unread for the caller; returns the refreshed unread count. */
  @PostMapping("/threads/{id}/unread")
  public UnreadCountView markUnread(
      @PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.markUnread(actor, id);
  }

  /** Delete a thread for the caller only (per-user soft-hide). The counterparty is unaffected. */
  @DeleteMapping("/threads/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.delete(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Thread-level unread count for the caller's inbox. */
  @GetMapping("/unread-count")
  public UnreadCountView unreadCount(@AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.unreadCount(actor);
  }
}
