package com.ihrms.review.dto;

import com.ihrms.agreement.dto.AgreementDtos.AgreementSummary;
import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.enums.OnboardingType;
import com.ihrms.onboarding.dto.OfferDtos.OfferRecordView;
import com.ihrms.onboarding.dto.OnboardingDtos.Form1View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form2View;
import com.ihrms.onboarding.dto.OnboardingDtos.Form3EntryView;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * HR verification & routing DTOs (§3.3/§3.4). The record surfaces the four forms (sensitive values
 * MASKED by default), the Form 4 uploads, and the generated PDFs. Records are springdoc-visible so
 * the web types regenerate.
 */
public final class ReviewDtos {

  private ReviewDtos() {}

  /**
   * Verify a form/document, or send it back for revision (§3.3). {@code REVISION_REQUESTED} requires a
   * {@code reason} — the HR note shown to the employee and recorded in the audit trail. {@code REJECTED}
   * is retained for compatibility but not offered per-item in the UI (terminal rejection is the
   * Manager's action at approval).
   */
  public record ReviewRequest(
      @NotBlank
          @Pattern(
              regexp = "VERIFIED|REJECTED|REVISION_REQUESTED",
              message = "decision must be VERIFIED, REJECTED or REVISION_REQUESTED")
          @Schema(allowableValues = {"VERIFIED", "REJECTED", "REVISION_REQUESTED"})
          String decision,
      @Size(max = 500, message = "Reason is too long") String reason) {}

  /**
   * HR APPROVES a verified employee (§3.3). The team is NOT a choice — it is resolved server-side as the
   * employee's onboarding-HR's team (the system's existing scoping rule); only an optional {@code note} is
   * taken. Valid only while the employee is HR_VERIFIED.
   */
  public record ApproveRequest(@Size(max = 500, message = "Note is too long") String note) {}

  /** HR terminally REJECTS the application (§3.3). A {@code note} is required — it is recorded in the audit. */
  public record RejectRequest(
      @NotBlank(message = "Add a note describing why the application is rejected")
          @Size(max = 500, message = "Note is too long")
          String note) {}

  /** {@code viewUrl} is a short-lived presigned GET — the raw storage key is never exposed (§6). */
  public record RecordDocument(
      String id,
      DocumentType docType,
      Integer groupIndex,
      String fileName,
      String mimeType,
      String sha256,
      DocumentStatus status,
      String revisionNote,
      String uploadedAt,
      String viewUrl) {}

  /** A generated onboarding PDF with a short-lived presigned view URL. */
  public record RecordGeneratedDocument(
      String id,
      GeneratedDocumentKind kind,
      String fileName,
      String sha256,
      String generatedAt,
      String viewUrl) {}

  /**
   * The full employee record HR/Manager reviews: intake fields + the four forms (sensitive values
   * masked) + uploads + generated PDFs. {@code reviewComplete} gates routing; {@code employeeCode}
   * is null until approval; {@code sensitiveRevealable} tells the UI a masked value can be revealed.
   */
  public record EmployeeRecordView(
      String id,
      String employeeCode,
      String fullName,
      String email,
      String designation,
      String dateOfJoining,
      EmployeeStatus status,
      boolean reviewComplete,
      boolean sensitiveRevealable,
      // Read-only mailbox state (§8, Stage 5): whether internal credentials have been assigned and, if so,
      // the assigned address. The password/hash is NEVER exposed here — only via the one-time assign result.
      boolean credentialsAssigned,
      String mailAddress,
      // The company's mail domain — so the HR "Assign mailbox" preview shows the address that will ACTUALLY
      // be created (localpart@mailDomain), not a guess from the acting HR's own login email.
      String mailDomain,
      Form1View form1,
      Form2View form2,
      List<Form3EntryView> form3,
      List<RecordDocument> documents,
      List<RecordGeneratedDocument> generatedDocuments,
      // Aadhaar (typed on AUP completion) — masked here, revealed via the audited reveal action (§6).
      String aadhaarNumber,
      // Post-approval agreements (§Agreements): per-type status + presigned download when completed.
      List<AgreementSummary> agreements,
      // The Offer Letter (§3.2): status + dates only. Null when there is no offer (a pre-feature employee).
      // The PDF (which carries the salary) is fetched via GET /employees/{id}/offer/pdf (role-gated), NOT here.
      OfferRecordView offer,
      // Whether HR has deactivated the account (§3.6) — the "Deactivated" chip shown alongside OFFBOARDED.
      boolean accountDeactivated,
      // When the active onboarding invite was last sent (§3.2/§6) — drives the HR "Resend invite" surface for
      // INVITED employees. Null once there is no active token (e.g. after onboarding completes).
      String inviteSentAt,
      // How the record was opened (§3.2) — an EXISTING_EMPLOYEE's record is HR-entered (no offer, no invite).
      OnboardingType onboardingType) {}

  /** The plaintext sensitive values returned by the explicit, audited reveal action (§6). */
  public record RevealedSensitive(
      Form1View form1, Form2View form2, List<Form3EntryView> form3, String aadhaarNumber) {}

  /**
   * The outcome of an HR approve/reject (§3.3). On approve: the freshly-minted {@code employeeCode} (§5),
   * the joined team, and the notified manager (null when the team has no manager). On reject: status
   * REJECTED with the team/manager fields null.
   */
  public record DecisionResult(
      String employeeCode,
      EmployeeStatus status,
      String teamId,
      String teamName,
      String managerName) {}

  /**
   * HR assigns an APPROVED employee internal credentials (§8, Stage 5). {@code localPart} forms
   * {@code localPart@companyDomain}; {@code password} is OPTIONAL — omit it and the system generates a
   * strong one (the default). Re-issuing regenerates + re-emails.
   */
  public record AssignCredentialsRequest(
      @NotBlank(message = "A mailbox name is required")
          @Size(max = 64, message = "Mailbox name is too long")
          String localPart,
      @Size(min = 8, message = "Use at least 8 characters") String password) {}

  /**
   * The assigned mailbox address; {@code password} is echoed ONCE (dev only) so HR can hand it over —
   * it is never stored in plaintext or returned again. {@code emailedTo} is the personal address it went to.
   */
  public record AssignCredentialsResult(
      String employeeId,
      String mailAddress,
      String emailedTo,
      String credentialsAssignedAt,
      @io.swagger.v3.oas.annotations.media.Schema(description = "Echoed once in dev; null in prod")
          String password) {}
}
