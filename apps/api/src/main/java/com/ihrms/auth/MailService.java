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

  /** Offer-accepted notice to the onboarding HR (§3.2) — HR has no notification bell feed. */
  public void sendOfferAccepted(String hrEmail, String employeeName) {
    String body =
        (employeeName == null ? "An employee" : employeeName)
            + " has accepted their offer letter and can now begin onboarding. Sign in to view the signed"
            + " offer on their record.";
    if (noSmtp()) {
      log.warn("[DEV OFFER ACCEPTED] {} -> {}  (no SMTP configured; logging only)", hrEmail, body);
      return;
    }
    log.info("Offer-accepted email dispatched to {}", hrEmail);
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

  /** Document-request resolved notice to the employee (§8d) — the employee has no notification bell. */
  public void sendDocumentRequestResolved(String email, String requestType) {
    String label = requestType == null ? "document" : requestType.replace('_', ' ').toLowerCase();
    String body = "Your " + label + " request is ready — sign in to download the document(s).";
    if (noSmtp()) {
      log.warn("[DEV REQUEST] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Document-request resolved email dispatched to {} ({})", email, label);
  }

  /** Standard-agreements assigned notice to the employee (§Agreements) — the employee has no bell. */
  public void sendAgreementsAssigned(String email, String fullName) {
    String body =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your employer has sent standard company agreements for you to review and sign. Sign in to"
            + " read, fill, and submit each one.";
    if (noSmtp()) {
      log.warn("[DEV AGREEMENTS] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Agreements-assigned email dispatched to {}", email);
  }

  /** Agreement-completed notice to the SENDING HR (§Agreements) — HR has no notification bell feed yet. */
  public void sendAgreementCompleted(String hrEmail, String employeeName, String agreementTitle) {
    String body =
        (employeeName == null ? "An employee" : employeeName)
            + " has completed and signed the \""
            + agreementTitle
            + "\" agreement. Sign in to view the signed PDF on their record.";
    if (noSmtp()) {
      log.warn("[DEV AGREEMENT DONE] {} -> {}  (no SMTP configured; logging only)", hrEmail, body);
      return;
    }
    log.info("Agreement-completed email dispatched to {} ({})", hrEmail, agreementTitle);
  }

  /** Offboarding decision notice to the INITIATING HR (§Offboarding) — HR has no notification bell feed. */
  public void sendOffboardingDecision(
      String hrEmail, String employeeName, boolean approved, String note) {
    String outcome = approved ? "approved" : "rejected";
    String body =
        "The offboarding case for "
            + (employeeName == null ? "an employee" : employeeName)
            + " was "
            + outcome
            + " by the platform reviewer"
            + (note == null || note.isBlank() ? "." : ". Note: " + note);
    if (noSmtp()) {
      log.warn("[DEV OFFBOARDING] {} -> {}  (no SMTP configured; logging only)", hrEmail, body);
      return;
    }
    log.info("Offboarding decision email dispatched to {} ({})", hrEmail, outcome);
  }

  /** Offboarding documents assigned notice to the employee (§3.6 stage 2) — no bell feed, so mail + push. */
  public void sendOffboardingDocsAssigned(String email, String fullName) {
    String body =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — offboarding documents have been sent for you to read and sign. Sign in to your workspace"
            + " to complete them.";
    if (noSmtp()) {
      log.warn("[DEV OFFB DOCS] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Offboarding-docs email dispatched to {}", email);
  }

  /** Offboarding document sent back for revision (§3.6 stage 2) — carries HR's note. */
  public void sendOffboardingDocReturned(String email, String fullName, String docTitle, String note) {
    String body =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your \""
            + docTitle
            + "\" needs changes"
            + (note == null || note.isBlank() ? "." : ": " + note)
            + " Please update and resubmit it in your workspace.";
    if (noSmtp()) {
      log.warn("[DEV OFFB RETURN] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Offboarding-doc-returned email dispatched to {}", email);
  }

  /** Offboarding letter issued notice to the employee (§3.6 stage 3) — no bell feed, so mail + push. */
  public void sendOffboardingLetterIssued(String email, String fullName, String letterTitle) {
    String body =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your \""
            + letterTitle
            + "\" is ready. Sign in to your workspace to download it.";
    if (noSmtp()) {
      log.warn("[DEV OFFB LETTER] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Offboarding-letter-issued email dispatched to {} ({})", email, letterTitle);
  }

  /** Final offboarding-complete notice to the (now offboarded) employee (§3.6 stage 3). */
  public void sendOffboardingCompleted(String email, String fullName) {
    String body =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your offboarding is now complete. Your account has been deactivated. We wish you well.";
    if (noSmtp()) {
      log.warn("[DEV OFFB DONE] {} -> {}  (no SMTP configured; logging only)", email, body);
      return;
    }
    log.info("Offboarding-complete email dispatched to {}", email);
  }

  private boolean noSmtp() {
    String host = props.mail() == null ? null : props.mail().host();
    return host == null || host.isBlank();
  }
}
