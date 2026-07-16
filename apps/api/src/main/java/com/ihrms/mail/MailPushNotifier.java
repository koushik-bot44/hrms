package com.ihrms.mail;

import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.Message;
import com.ihrms.domain.model.MessageRecipient;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.MessageRecipientRepository;
import com.ihrms.domain.repository.MessageRepository;
import com.ihrms.domain.repository.UserRepository;
import com.ihrms.push.PushService;
import com.ihrms.push.PushService.PrincipalRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * New-mail OS push (§ Web Push, Stage N3). When a message is delivered (compose / reply / reply-all),
 * every recipient (TO + CC + BCC) EXCEPT the sender gets a push titled with the sender, bodied with the
 * subject, deep-linking to {@code /mail}. The payload carries NO recipient information, so a BCC
 * recipient's notification reveals nothing about who else got the message — and nothing ever leaks the
 * BCC list. Called from the CONTROLLER after the send transaction has committed (the same post-commit
 * pattern as the leave auto-mail) so the recipient rows are visible here; fully BEST-EFFORT — any failure
 * is logged and swallowed, never affecting the already-committed mail. Recipients without a push
 * subscription simply get nothing ({@link PushService#sendToPrincipal} no-ops for them).
 */
@Component
public class MailPushNotifier {

  private static final Logger log = LoggerFactory.getLogger(MailPushNotifier.class);
  private static final int SNIPPET = 140;

  private final MessageRepository messages;
  private final MessageRecipientRepository recipients;
  private final UserRepository users;
  private final EmployeeRepository employees;
  private final PushService push;

  public MailPushNotifier(
      MessageRepository messages,
      MessageRecipientRepository recipients,
      UserRepository users,
      EmployeeRepository employees,
      PushService push) {
    this.messages = messages;
    this.recipients = recipients;
    this.employees = employees;
    this.users = users;
    this.push = push;
  }

  /** Notify every recipient of {@code messageId} except the sender. Best-effort: NEVER throws. */
  public void notifyRecipients(String messageId) {
    try {
      Message message = messages.findById(messageId).orElse(null);
      if (message == null) {
        return;
      }
      String title = "New message from " + senderDisplayName(message);
      String body = subjectOrSnippet(message);
      for (MessageRecipient recipient : recipients.findByMessageId(messageId)) {
        if (isSender(message, recipient)) {
          continue; // never notify the author of their own message (defensive: self-addressed mail)
        }
        try {
          push.sendToPrincipal(refOf(recipient), title, body, "/mail");
        } catch (RuntimeException e) {
          // sendToPrincipal is itself best-effort; this guard keeps one bad recipient from stopping the rest.
          log.warn("Mail push to recipient {} skipped: {}", recipient.getId(), e.getMessage());
        }
      }
    } catch (RuntimeException e) {
      log.warn("Mail push notifications skipped (best-effort): {}", e.getMessage());
    }
  }

  /** companyId is not needed for delivery — subscriptions are looked up purely by principal id. */
  private static PrincipalRef refOf(MessageRecipient recipient) {
    return recipient.getRecipientUserId() != null
        ? PrincipalRef.forUser(recipient.getRecipientUserId(), null)
        : PrincipalRef.forEmployee(recipient.getRecipientEmployeeId(), null);
  }

  private static boolean isSender(Message message, MessageRecipient recipient) {
    return (recipient.getRecipientUserId() != null
            && recipient.getRecipientUserId().equals(message.getSenderUserId()))
        || (recipient.getRecipientEmployeeId() != null
            && recipient.getRecipientEmployeeId().equals(message.getSenderEmployeeId()));
  }

  private String senderDisplayName(Message message) {
    if (message.getSenderUserId() != null) {
      return users
          .findById(message.getSenderUserId())
          .map(User::getName)
          .filter(n -> n != null && !n.isBlank())
          .orElse("a colleague");
    }
    return employees
        .findById(message.getSenderEmployeeId())
        .map(Employee::getFullName)
        .filter(n -> n != null && !n.isBlank())
        .orElse("a colleague");
  }

  /** Every message carries the thread subject; the snippet fallback is purely defensive. */
  private static String subjectOrSnippet(Message message) {
    String subject = message.getSubject();
    if (subject != null && !subject.isBlank()) {
      return subject;
    }
    String s = message.getBody() == null ? "" : message.getBody().strip().replaceAll("\\s+", " ");
    return s.length() <= SNIPPET ? s : s.substring(0, SNIPPET - 1) + "…";
  }
}
