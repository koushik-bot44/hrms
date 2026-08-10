package com.ihrms.contact;

import com.ihrms.auth.MailService;
import com.ihrms.config.AppProperties;
import com.ihrms.contact.dto.ContactDtos.ContactRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles a public contact-form submission (§8d): soft anti-spam (honeypot + minimum fill time → silent
 * drop), the hard per-IP/global rate limit ({@link ContactRateLimiter}), then a SYNCHRONOUS email to the
 * contact inbox with Reply-To = the submitter. Nothing is stored; the only log is the org + email (never the
 * message body). A silent drop and a genuine send both return normally — the controller answers 200 either way.
 */
@Service
public class ContactService {

  private static final Logger log = LoggerFactory.getLogger(ContactService.class);
  private static final long MIN_FILL_MS = 3_000; // faster than this ⇒ almost certainly a bot

  private final MailService mail;
  private final ContactRateLimiter limiter;
  private final AppProperties props;

  public ContactService(MailService mail, ContactRateLimiter limiter, AppProperties props) {
    this.mail = mail;
    this.limiter = limiter;
    this.props = props;
  }

  /** @return true if an email was actually sent; false when silently dropped (honeypot / too fast). */
  public boolean submit(ContactRequest req, String ip) {
    // Soft anti-spam — pretend success, send nothing (bots believe they got through).
    if (req.website() != null && !req.website().isBlank()) {
      return false;
    }
    if (req.elapsedMs() != null && req.elapsedMs() < MIN_FILL_MS) {
      return false;
    }

    // Hard guard — throws 429 over the per-IP / global hourly cap.
    limiter.check(ip);

    String org = req.organization().trim();
    String email = req.email().trim();
    String phone = req.phone() == null || req.phone().isBlank() ? null : req.phone().trim();
    mail.sendContactEnquiry(
        inbox(), req.name().trim(), email, org, phone,
        req.message() == null ? null : req.message().trim());
    log.info("Contact enquiry from {} <{}>", org, email); // minimal: org + email only, no message body
    return true;
  }

  private String inbox() {
    String configured = props.mail() == null ? null : props.mail().contactInbox();
    return configured == null || configured.isBlank() ? "info@hrorg.in" : configured;
  }
}
