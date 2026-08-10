package com.ihrms.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/**
 * Real outbound delivery via Zoho (or any) SMTP, on the auto-configured {@link JavaMailSender} (spring.mail.*,
 * 587/STARTTLS or 465/SSL). Every message carries From={@code MAIL_FROM} and Reply-To={@code MAIL_REPLY_TO}
 * and a multipart/alternative body (plain text + branded HTML). {@link #build} is separated from {@link #send}
 * so a message can be constructed and asserted without touching the network.
 */
public class SmtpMailer implements Mailer {

  private static final Logger log = LoggerFactory.getLogger(SmtpMailer.class);

  private final JavaMailSender sender;
  private final String from;
  private final String replyTo;

  public SmtpMailer(JavaMailSender sender, String from, String replyTo) {
    this.sender = sender;
    this.from = from;
    this.replyTo = replyTo;
  }

  /** Construct the MIME message (From/Reply-To + text+html alternative parts) — does NOT send. */
  public MimeMessage build(OutboundEmail email) throws Exception {
    MimeMessage message = sender.createMimeMessage();
    MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
    helper.setTo(email.to());
    helper.setSubject(email.subject());
    if (from != null && !from.isBlank()) {
      helper.setFrom(from);
    }
    if (replyTo != null && !replyTo.isBlank()) {
      helper.setReplyTo(replyTo);
    }
    // (text, html) → multipart/alternative: the client shows HTML, falls back to text.
    helper.setText(email.text() == null ? "" : email.text(), email.html() == null ? "" : email.html());
    return message;
  }

  @Override
  public void send(OutboundEmail email) {
    try {
      sender.send(build(email));
      log.debug("Mail sent to {} (subject '{}')", email.to(), email.subject());
    } catch (Exception e) {
      // Surfaced as a RuntimeException so MailService's best-effort dispatch logs recipient+subject (no body).
      throw new IllegalStateException("SMTP send failed", e);
    }
  }

  @Override
  public boolean isReal() {
    return true;
  }
}
