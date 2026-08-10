package com.ihrms.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default transport when no SMTP is configured (local dev + all tests): logs the email's {@code devLog}
 * line verbatim — exactly the {@code [DEV OTP]} / {@code [DEV INVITE]} / … output the app produced before,
 * so end-to-end flows stay walkable from the logs and no network is ever touched. Never throws.
 */
public class DevLogMailer implements Mailer {

  private static final Logger log = LoggerFactory.getLogger(DevLogMailer.class);

  @Override
  public void send(OutboundEmail email) {
    log.warn("{}", email.devLog());
  }

  @Override
  public boolean isReal() {
    return false;
  }
}
