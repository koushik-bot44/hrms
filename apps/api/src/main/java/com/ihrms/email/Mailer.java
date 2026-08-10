package com.ihrms.email;

/**
 * The outbound-email transport seam (contract §Outbound email). Exactly one implementation is active,
 * selected by config presence at startup ({@link MailConfig}): {@link SmtpMailer} when {@code SMTP_HOST} is
 * set, else {@link DevLogMailer}. Callers never touch this directly — {@code MailService} owns the after-commit
 * + async + best-effort dispatch and routes every mail through the selected {@code Mailer}.
 */
public interface Mailer {

  /** Deliver one email. May throw; {@code MailService}'s dispatch wraps every call best-effort. */
  void send(OutboundEmail email);

  /** True when real delivery is active (SMTP). Drives the devOtp gate so it can never drift from the mode. */
  boolean isReal();
}
