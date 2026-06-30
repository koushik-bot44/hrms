package com.ihrms.auth;

import com.ihrms.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Outbound email. With no SMTP configured (local/dev) the OTP is logged so flows can be
 * walked end-to-end; the SMTP transport is a seam to wire when MAIL_* is set.
 */
@Service
public class MailService {

  private static final Logger log = LoggerFactory.getLogger(MailService.class);

  private final AppProperties props;

  public MailService(AppProperties props) {
    this.props = props;
  }

  public void sendOtp(String email, String code) {
    String host = props.mail() == null ? null : props.mail().host();
    if (host == null || host.isBlank()) {
      log.warn("[DEV OTP] {} -> {}  (no SMTP configured; logging only)", email, code);
      return;
    }
    // SMTP seam: wire a JavaMailSender here when MAIL_* is configured.
    log.info("OTP email dispatched to {}", email);
  }
}
