package com.ihrms.auth;

import com.ihrms.email.Mailer;
import com.ihrms.email.MailTemplate;
import com.ihrms.email.OutboundEmail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The app-facing outbound-email API (contract §Outbound email). The 16 semantic methods below build each
 * message (subject + branded HTML + plain text + the exact legacy dev-log line) and hand it to the selected
 * {@link Mailer} through one dispatch path: AFTER the current transaction commits (so a rollback sends
 * nothing), ASYNC on a small pool (so slow SMTP never blocks the request), and BEST-EFFORT (a send failure is
 * logged with recipient+subject — never the body — and NEVER fails or blocks the business action). This is the
 * OUTBOUND transport to real inboxes; the in-app INTERNAL mail client ({@code com.ihrms.mail}) is unrelated.
 */
@Service
public class MailService {

  private static final Logger log = LoggerFactory.getLogger(MailService.class);
  private static final String DEV_SUFFIX = "  (no SMTP configured; logging only)";

  private final Mailer mailer;
  private final TaskExecutor executor;

  public MailService(Mailer mailer, @Qualifier("mailExecutor") TaskExecutor executor) {
    this.mailer = mailer;
    this.executor = executor;
  }

  /** Whether real (SMTP) delivery is active — the single source the devOtp gate binds to (never a stray flag). */
  public boolean isRealDelivery() {
    return mailer.isReal();
  }

  // --- auth / provisioning ---------------------------------------------------

  public void sendOtp(String email, String code) {
    String text =
        "Your hrorg.in sign-in code is: " + code
            + "\n\nIt expires shortly. If you didn't request this, you can ignore this email.";
    dispatch(email, "Your hrorg.in sign-in code", text, "[DEV OTP] " + email + " -> " + code + DEV_SUFFIX);
  }

  /** Initial credentials for a newly provisioned Company Admin. */
  public void sendCompanyAdminInvite(String email, String companyName, String tempPassword) {
    String text =
        "You've been set up as the administrator for " + companyName + " on hrorg.in.\n\n"
            + "Sign in with:\nEmail: " + email + "\nTemporary password: " + tempPassword
            + "\n\nYou'll be asked to change your password after signing in.";
    dispatch(
        email,
        "Your hrorg.in admin access for " + companyName,
        text,
        "[DEV INVITE] Company Admin for \"" + companyName + "\" -> " + email + " / " + tempPassword + DEV_SUFFIX);
  }

  /** Initial credentials for a newly created HR/Manager staff user. */
  public void sendStaffInvite(String email, String role, String tempPassword) {
    String text =
        "You've been added to hrorg.in as " + role + ".\n\n"
            + "Sign in with:\nEmail: " + email + "\nTemporary password: " + tempPassword
            + "\n\nYou'll be asked to change your password after signing in.";
    dispatch(
        email,
        "Your hrorg.in access",
        text,
        "[DEV INVITE] " + role + " -> " + email + " / " + tempPassword + DEV_SUFFIX);
  }

  /**
   * Selection email at onboarding (§3.2): a friendly note + the employee-login link. No ID — the
   * unique employee ID is allocated only on Manager approval (§5).
   */
  public void sendEmployeeSelection(
      String email, String fullName, String designation, String companyName, String loginUrl) {
    String text =
        String.format(
            "Hello %s, you are selected to the %s role in %s. Sign in to start your onboarding: %s",
            fullName, designation, companyName, loginUrl);
    dispatch(email, "You're invited to onboard at " + companyName, text, "[DEV SELECTION] " + email + " -> " + text + DEV_SUFFIX);
  }

  /**
   * Internal credentials for an approved employee (§8, Stage 5): sent to their PERSONAL email — their new
   * mailbox address IS the login email, the password, and the {@code /login} link.
   */
  public void sendEmployeeCredentials(
      String personalEmail, String mailAddress, String password, String loginUrl) {
    String text =
        "Your hrorg.in account is ready.\n\nSign in at " + loginUrl
            + "\nEmail: " + mailAddress + "\nPassword: " + password;
    dispatch(
        personalEmail,
        "Your hrorg.in mailbox & sign-in",
        text,
        "[DEV CREDENTIALS] " + personalEmail + " -> sign in at " + loginUrl + " as " + mailAddress + " / " + password + DEV_SUFFIX);
  }

  /** Welcome the approved employee with their newly-minted unique ID (§3.3/§5). */
  public void sendEmployeeWelcome(String email, String fullName, String employeeCode) {
    String text = String.format("Welcome aboard, %s! Your employee ID is %s.", fullName, employeeCode);
    dispatch(email, "Welcome to hrorg.in — your employee ID", text, "[DEV WELCOME] " + email + " -> " + text + DEV_SUFFIX);
  }

  // --- notifications to staff / employees ------------------------------------

  /** Offer-accepted notice to the onboarding HR (§3.2) — HR has no notification bell feed. */
  public void sendOfferAccepted(String hrEmail, String employeeName) {
    String text =
        (employeeName == null ? "An employee" : employeeName)
            + " has accepted their offer letter and can now begin onboarding. Sign in to view the signed"
            + " offer on their record.";
    dispatch(hrEmail, "Offer accepted", text, "[DEV OFFER ACCEPTED] " + hrEmail + " -> " + text + DEV_SUFFIX);
  }

  /** Leave decision notice to the employee (§8b) — the employee has no notification bell. */
  public void sendLeaveDecision(String email, boolean approved, String note) {
    String outcome = approved ? "approved" : "rejected";
    String text =
        "Your leave request was " + outcome + (note == null || note.isBlank() ? "." : ". Note: " + note);
    dispatch(email, "Your leave request was " + outcome, text, "[DEV LEAVE] " + email + " -> " + text + DEV_SUFFIX);
  }

  /** Document-request resolved notice to the employee (§8d) — the employee has no notification bell. */
  public void sendDocumentRequestResolved(String email, String requestType) {
    String label = requestType == null ? "document" : requestType.replace('_', ' ').toLowerCase();
    String text = "Your " + label + " request is ready — sign in to download the document(s).";
    dispatch(email, "Your " + label + " request is ready", text, "[DEV REQUEST] " + email + " -> " + text + DEV_SUFFIX);
  }

  /** Standard-agreements assigned notice to the employee (§Agreements) — the employee has no bell. */
  public void sendAgreementsAssigned(String email, String fullName) {
    String text =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your employer has sent standard company agreements for you to review and sign. Sign in to"
            + " read, fill, and submit each one.";
    dispatch(email, "Agreements to review and sign", text, "[DEV AGREEMENTS] " + email + " -> " + text + DEV_SUFFIX);
  }

  /** Agreement-completed notice to the SENDING HR (§Agreements) — HR has no notification bell feed yet. */
  public void sendAgreementCompleted(String hrEmail, String employeeName, String agreementTitle) {
    String text =
        (employeeName == null ? "An employee" : employeeName)
            + " has completed and signed the \"" + agreementTitle
            + "\" agreement. Sign in to view the signed PDF on their record.";
    dispatch(hrEmail, "Agreement completed: " + agreementTitle, text, "[DEV AGREEMENT DONE] " + hrEmail + " -> " + text + DEV_SUFFIX);
  }

  /** Offboarding decision notice to the INITIATING HR (§Offboarding) — HR has no notification bell feed. */
  public void sendOffboardingDecision(
      String hrEmail, String employeeName, boolean approved, String note) {
    String outcome = approved ? "approved" : "rejected";
    String text =
        "The offboarding case for " + (employeeName == null ? "an employee" : employeeName) + " was " + outcome
            + " by the platform reviewer" + (note == null || note.isBlank() ? "." : ". Note: " + note);
    dispatch(hrEmail, "Offboarding " + outcome, text, "[DEV OFFBOARDING] " + hrEmail + " -> " + text + DEV_SUFFIX);
  }

  /** Offboarding documents assigned notice to the employee (§3.6 stage 2) — no bell feed, so mail + push. */
  public void sendOffboardingDocsAssigned(String email, String fullName) {
    String text =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — offboarding documents have been sent for you to read and sign. Sign in to your workspace"
            + " to complete them.";
    dispatch(email, "Offboarding documents to sign", text, "[DEV OFFB DOCS] " + email + " -> " + text + DEV_SUFFIX);
  }

  /** Offboarding document sent back for revision (§3.6 stage 2) — carries HR's note. */
  public void sendOffboardingDocReturned(String email, String fullName, String docTitle, String note) {
    String text =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your \"" + docTitle + "\" needs changes"
            + (note == null || note.isBlank() ? "." : ": " + note)
            + " Please update and resubmit it in your workspace.";
    dispatch(email, "Changes needed: " + docTitle, text, "[DEV OFFB RETURN] " + email + " -> " + text + DEV_SUFFIX);
  }

  /** Offboarding letter issued notice to the employee (§3.6 stage 3) — no bell feed, so mail + push. */
  public void sendOffboardingLetterIssued(String email, String fullName, String letterTitle) {
    String text =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your \"" + letterTitle + "\" is ready. Sign in to your workspace to download it.";
    dispatch(email, "Your " + letterTitle + " is ready", text, "[DEV OFFB LETTER] " + email + " -> " + text + DEV_SUFFIX);
  }

  /** Final offboarding-complete notice to the (now offboarded) employee (§3.6 stage 3). */
  public void sendOffboardingCompleted(String email, String fullName) {
    String text =
        (fullName == null ? "Hello" : "Hello " + fullName)
            + " — your offboarding is now complete. Your account has been deactivated. We wish you well.";
    dispatch(email, "Your offboarding is complete", text, "[DEV OFFB DONE] " + email + " -> " + text + DEV_SUFFIX);
  }

  // --- dispatch: after-commit + async + best-effort --------------------------

  private void dispatch(String to, String subject, String text, String devLog) {
    OutboundEmail email = new OutboundEmail(to, subject, MailTemplate.render(text), text, devLog);
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              executor.execute(() -> deliver(email));
            }
          });
    } else {
      executor.execute(() -> deliver(email));
    }
  }

  /** Best-effort delivery: a failure is logged (recipient + subject, never the body) and swallowed. */
  private void deliver(OutboundEmail email) {
    try {
      mailer.send(email);
    } catch (Exception e) {
      log.warn("Outbound mail failed to {} (subject '{}'): {}", email.to(), email.subject(), e.toString());
    }
  }
}
