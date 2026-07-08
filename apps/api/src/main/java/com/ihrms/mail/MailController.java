package com.ihrms.mail;

import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.mail.dto.MailDtos.InboxPage;
import com.ihrms.mail.dto.MailDtos.MailPartyView;
import com.ihrms.mail.dto.MailDtos.MessageView;
import com.ihrms.mail.dto.MailDtos.SendMessageRequest;
import com.ihrms.mail.dto.MailDtos.SendMessageResult;
import com.ihrms.mail.dto.MailDtos.SentPage;
import com.ihrms.mail.dto.MailDtos.UnreadCountView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal mailbox (ARCHITECTURE.md §8). Every handler acts as the authenticated staff caller on their
 * OWN mailbox — no role gate here (the SecurityConfig rule keeps employees out; the send graph is the
 * real gate, enforced in the service and surfaced as 403). Never role-annotate this controller:
 * {@code @PreAuthorize} denials would surface as 500, not 403.
 */
@RestController
@RequestMapping("/mail")
public class MailController {

  private final InternalMailService mail;

  public MailController(InternalMailService mail) {
    this.mail = mail;
  }

  /** The accounts the caller may message (the send graph as a concrete list). */
  @GetMapping("/contacts")
  public List<MailPartyView> contacts(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return mail.contacts(actor);
  }

  /** Send a text message to one recipient. 403 if the send graph forbids it. */
  @PostMapping("/messages")
  public SendMessageResult send(
      @Valid @RequestBody SendMessageRequest body,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return mail.send(actor, body, request.getRemoteAddr());
  }

  /** Inbox — messages addressed to the caller, newest first, with a read flag. */
  @GetMapping("/inbox")
  public InboxPage inbox(
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.inbox(actor, pageable);
  }

  /** Sent — messages the caller sent, newest first. */
  @GetMapping("/sent")
  public SentPage sent(
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      @PageableDefault(size = 20) Pageable pageable) {
    return mail.sent(actor, pageable);
  }

  /** One message — only if the caller is the sender or a recipient (else 403). Marks it read. */
  @GetMapping("/messages/{id}")
  public MessageView message(
      @PathVariable String id,
      @AuthenticationPrincipal IhrmsPrincipal.User actor,
      HttpServletRequest request) {
    return mail.view(actor, id, request.getRemoteAddr());
  }

  /** Unread count for the caller's inbox. */
  @GetMapping("/unread-count")
  public UnreadCountView unreadCount(@AuthenticationPrincipal IhrmsPrincipal.User actor) {
    return mail.unreadCount(actor);
  }
}
