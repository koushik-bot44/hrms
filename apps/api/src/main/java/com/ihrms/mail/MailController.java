package com.ihrms.mail;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.mail.dto.MailDtos.AttachmentDownload;
import com.ihrms.mail.dto.MailDtos.AttachmentUpload;
import com.ihrms.mail.dto.MailDtos.AttachmentUploadRequest;
import com.ihrms.mail.dto.MailDtos.DraftPage;
import com.ihrms.mail.dto.MailDtos.DraftView;
import com.ihrms.mail.dto.MailDtos.LabelNameRequest;
import com.ihrms.mail.dto.MailDtos.LabelView;
import com.ihrms.mail.dto.MailDtos.MailPartyView;
import com.ihrms.mail.dto.MailDtos.ReplyRequest;
import com.ihrms.mail.dto.MailDtos.SaveDraftRequest;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

  /**
   * Search + FILTER the caller's own mail (§8). {@code q} (subject/body text) ANDs with optional {@code
   * from}, {@code after}/{@code before} (ISO-8601 instants), {@code hasAttachment}/{@code unread}/{@code
   * starred}, and {@code scope} (ALL/INBOX/SENT/STARRED/ARCHIVE; default ALL). All optional.
   */
  @GetMapping("/search")
  public ThreadPage search(
      @RequestParam(name = "q", required = false) String q,
      @RequestParam(name = "from", required = false) String from,
      @RequestParam(name = "after", required = false) String after,
      @RequestParam(name = "before", required = false) String before,
      @RequestParam(name = "hasAttachment", required = false, defaultValue = "false") boolean hasAttachment,
      @RequestParam(name = "unread", required = false, defaultValue = "false") boolean unread,
      @RequestParam(name = "starred", required = false, defaultValue = "false") boolean starred,
      @RequestParam(name = "scope", required = false) String scope,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.search(actor, q, from, after, before, hasAttachment, unread, starred, scope, pageable);
  }

  /** Starred — the caller's starred conversations (per-user), same shape + scoping as Inbox. */
  @GetMapping("/starred")
  public ThreadPage starred(
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.starred(actor, pageable);
  }

  /** Archived — the caller's archived conversations (per-user); these are HIDDEN from their Inbox. */
  @GetMapping("/archived")
  public ThreadPage archived(
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.archived(actor, pageable);
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

  /** Star a thread for the caller only (per-user; idempotent). Participant only (404/403). */
  @PostMapping("/threads/{id}/star")
  public ResponseEntity<Void> star(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.star(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Unstar a thread for the caller only (per-user; idempotent). Participant only (404/403). */
  @DeleteMapping("/threads/{id}/star")
  public ResponseEntity<Void> unstar(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.unstar(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Archive a thread for the caller only (per-user; idempotent) — hides it from their Inbox. 404/403. */
  @PostMapping("/threads/{id}/archive")
  public ResponseEntity<Void> archive(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.archive(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Unarchive a thread for the caller only (per-user; idempotent) — returns it to their Inbox. 404/403. */
  @DeleteMapping("/threads/{id}/archive")
  public ResponseEntity<Void> unarchive(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.unarchive(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Thread-level unread count for the caller's inbox. */
  @GetMapping("/unread-count")
  public UnreadCountView unreadCount(@AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.unreadCount(actor);
  }

  // --- Drafts (author-private, unsent; §8) ----------------------------------

  /** The caller's drafts, most-recently-edited first (author-only). */
  @GetMapping("/drafts")
  public DraftPage listDrafts(
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.listDrafts(actor, pageable);
  }

  /** Open one of the caller's drafts for editing (author-only; 404 otherwise). */
  @GetMapping("/drafts/{id}")
  public DraftView getDraft(@PathVariable String id, @AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.getDraft(actor, id);
  }

  /** Create a new draft — permissive (no send-graph check, no required fields). */
  @PostMapping("/drafts")
  public DraftView saveDraft(
      @RequestBody SaveDraftRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.saveDraft(actor, body, request.getRemoteAddr());
  }

  /** Replace an existing draft's contents (still permissive). Author-only. */
  @PutMapping("/drafts/{id}")
  public DraftView updateDraft(
      @PathVariable String id,
      @RequestBody SaveDraftRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.updateDraft(actor, id, body, request.getRemoteAddr());
  }

  /** Discard a draft (delete it + best-effort attachment cleanup). Author-only. */
  @DeleteMapping("/drafts/{id}")
  public ResponseEntity<Void> discardDraft(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.discardDraft(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /**
   * Send a draft — runs the REAL send path (canSendMail + validation + delivery + threading), then removes
   * the draft. On validation/permission failure the draft REMAINS and the error surfaces. Fires the
   * after-commit push to recipients, exactly like a normal compose.
   */
  @PostMapping("/drafts/{id}/send")
  public SendMessageResult sendDraft(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    SendMessageResult result = mail.sendDraft(actor, id, request.getRemoteAddr());
    pushNotifier.notifyRecipients(result.id()); // post-commit, best-effort (§ Web Push N3)
    return result;
  }

  // --- Labels (author-private tags; §8) -------------------------------------

  /** The caller's labels (alphabetical), each with its thread-tag count. */
  @GetMapping("/labels")
  public List<LabelView> listLabels(@AuthenticationPrincipal IhrmsPrincipal actor) {
    return mail.listLabels(actor);
  }

  /** Create a label (name unique per author, case-insensitive; 409 on a dup). */
  @PostMapping("/labels")
  public LabelView createLabel(
      @Valid @RequestBody LabelNameRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.createLabel(actor, body, request.getRemoteAddr());
  }

  /** Rename one of the caller's labels. Author-only (404 otherwise). */
  @PatchMapping("/labels/{id}")
  public LabelView renameLabel(
      @PathVariable String id,
      @Valid @RequestBody LabelNameRequest body,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    return mail.renameLabel(actor, id, body, request.getRemoteAddr());
  }

  /** Delete a label + its assignments (the threads themselves are untouched). Author-only. */
  @DeleteMapping("/labels/{id}")
  public ResponseEntity<Void> deleteLabel(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.deleteLabel(actor, id, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** The threads tagged with the caller's label (a label view). Author-only (404 otherwise). */
  @GetMapping("/labels/{id}/threads")
  public ThreadPage labelThreads(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.labelThreads(actor, id, pageable);
  }

  /** Tag a thread with one of the caller's labels (idempotent). Participant-only (403/404). */
  @PostMapping("/threads/{threadId}/labels/{labelId}")
  public ResponseEntity<Void> applyLabel(
      @PathVariable String threadId,
      @PathVariable String labelId,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.applyLabel(actor, threadId, labelId, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }

  /** Remove a label from a thread (idempotent). Author-only label + participant-only thread. */
  @DeleteMapping("/threads/{threadId}/labels/{labelId}")
  public ResponseEntity<Void> removeLabel(
      @PathVariable String threadId,
      @PathVariable String labelId,
      @AuthenticationPrincipal IhrmsPrincipal actor,
      HttpServletRequest request) {
    mail.removeLabel(actor, threadId, labelId, request.getRemoteAddr());
    return ResponseEntity.noContent().build();
  }
}
