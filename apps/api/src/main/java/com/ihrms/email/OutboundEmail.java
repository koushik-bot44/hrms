package com.ihrms.email;

/**
 * One transport-ready outbound email. Built by {@code MailService} (subject + branded {@code html} + plain
 * {@code text} + the exact legacy {@code devLog} line) and handed to the {@link Mailer}. {@code devLog} is
 * what {@link DevLogMailer} prints (preserving today's dev output verbatim, incl. secrets like the OTP);
 * {@link SmtpMailer} ignores it and sends {@code html}+{@code text}. No message body is ever logged on failure.
 *
 * <p>{@code replyTo} is a PER-MESSAGE Reply-To override: null → the mailer's default ({@code MAIL_REPLY_TO},
 * support@); set → that address instead (the public contact form points Reply-To at the SUBMITTER so replies
 * from the inbox reach the prospect directly).
 */
public record OutboundEmail(
    String to, String subject, String html, String text, String devLog, String replyTo) {}
