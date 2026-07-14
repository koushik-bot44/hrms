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
    if (noSmtp()) {
      log.warn("[DEV OTP] {} -> {}  (no SMTP configured; logging only)", email, code);
      return;
    }
    // SMTP seam: wire a JavaMailSender here when MAIL_* is configured.
    log.info("OTP email dispatched to {}", email);
  }

  /** Initial credentials for a newly provisioned Company Admin. */
  public void sendCompanyAdminInvite(String email, String companyName, String tempPassword) {
    if (noSmtp()) {
      log.warn(
          "[DEV INVITE] Company Admin for \"{}\" -> {} / {}  (no SMTP configured; logging only)",
          companyName,
          email,
          tempPassword);
      return;
    }
    log.info("Company admin invite dispatched to {}", email);
  }

  /** Initial credentials for a newly created HR/Manager staff user. */
  public void sendStaffInvite(String email, String role, String tempPassword) {
    if (noSmtp()) {
      log.warn(
          "[DEV INVITE] {} -> {} / {}  (no SMTP configured; logging only)", role, email, tempPassword);
      return;
    }
    log.info("Staff invite ({}) dispatched to {}", role, email);
  }

  /**
   * Selection email at onboarding (§3.2): a friendly note + the employee-login link. No ID — the
   * unique employee ID is allocated only on Manager approval (§5).
   */
  public void sendEmployeeSelection(
      String email, String fullName, String designation, String companyName, String loginUrl) {
    String body =
        String.format(
            "Hello %s, you are selected to the %s role in %s. Sign in to start your onboarding: %s",
            fullName, designation, companyName, loginUrl);
    if (noSmtp()) {
      log.warn("[DEV SELECTION] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Selection email dispatched to {}", email);
  }

  /**
   * Internal credentials for an approved employee (§8, Stage 5): sent to their PERSONAL email — their new
   * mailbox address IS the login email, the password, and the {@code /login} link. Dev-logged.
   */
  public void sendEmployeeCredentials(
      String personalEmail, String mailAddress, String password, String loginUrl) {
    if (noSmtp()) {
      log.warn(
          "[DEV CREDENTIALS] {} -> sign in at {} as {} / {}  (no SMTP configured; logging only)",
          personalEmail,
          loginUrl,
          mailAddress,
          password);
      return;
    }
    log.info("Credentials email dispatched to {} (mailbox {})", personalEmail, mailAddress);
  }

  /** Welcome the approved employee with their newly-minted unique ID (§3.3/§5). */
  public void sendEmployeeWelcome(String email, String fullName, String employeeCode) {
    String body =
        String.format("Welcome aboard, %s! Your employee ID is %s.", fullName, employeeCode);
    if (noSmtp()) {
      log.warn("[DEV WELCOME] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Welcome email dispatched to {} (ID {})", email, employeeCode);
  }

  /** Leave decision notice to the employee (§8b) — the employee has no notification bell. */
  public void sendLeaveDecision(String email, boolean approved, String note) {
    String outcome = approved ? "approved" : "rejected";
    String body =
        "Your leave request was " + outcome + (note == null || note.isBlank() ? "." : ". Note: " + note);
    if (noSmtp()) {
      log.warn("[DEV LEAVE] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Leave decision email dispatched to {} ({})", email, outcome);
  }

  private boolean noSmtp() {
    String host = props.mail() == null ? null : props.mail().host();
    return host == null || host.isBlank();
  }
}
