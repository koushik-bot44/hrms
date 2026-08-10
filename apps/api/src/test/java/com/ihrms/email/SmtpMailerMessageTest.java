package com.ihrms.email;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * A built SMTP message carries From=MAIL_FROM, Reply-To=MAIL_REPLY_TO, the subject, and BOTH a text/plain and
 * a text/html part — constructed and asserted WITHOUT sending (no network). {@link JavaMailSenderImpl} creates
 * the MIME message from an in-memory session; it is never connected.
 */
class SmtpMailerMessageTest {

  @Test
  void builtMessageCarriesFromReplyToAndBothParts() throws Exception {
    SmtpMailer mailer =
        new SmtpMailer(new JavaMailSenderImpl(), "hrorg.in <noreply@hrorg.in>", "support@hrorg.in");

    MimeMessage msg =
        mailer.build(
            new OutboundEmail(
                "employee@example.test",
                "Your hrorg.in sign-in code",
                "<p>Your code is 123456</p>",
                "Your code is 123456",
                "[DEV OTP] employee@example.test -> 123456  (no SMTP configured; logging only)"));

    assertThat(msg.getFrom()[0].toString()).contains("noreply@hrorg.in");
    assertThat(msg.getReplyTo()[0].toString()).contains("support@hrorg.in");
    assertThat(msg.getSubject()).isEqualTo("Your hrorg.in sign-in code");
    assertThat(msg.getAllRecipients()[0].toString()).isEqualTo("employee@example.test");

    // multipart/alternative → both parts present (assert on the raw MIME to avoid nesting assumptions).
    msg.saveChanges();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    msg.writeTo(out);
    String raw = out.toString();
    assertThat(msg.getContentType()).contains("multipart");
    assertThat(raw).contains("text/plain");
    assertThat(raw).contains("text/html");
  }
}
