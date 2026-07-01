package com.ihrms.review.dto;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
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

  /** Verify or reject a form/document; the optional reason is recorded in the audit trail. */
  public record ReviewRequest(
      @NotBlank
          @Pattern(regexp = "VERIFIED|REJECTED", message = "decision must be VERIFIED or REJECTED")
          @Schema(allowableValues = {"VERIFIED", "REJECTED"})
          String decision,
      @Size(max = 500, message = "Reason is too long") String reason) {}

  /** Route the reviewed record to the team's Manager for approval. */
  public record RouteToManagerRequest(
      @Size(max = 500, message = "Note is too long") String note) {}

  /** {@code viewUrl} is a short-lived presigned GET — the raw storage key is never exposed (§6). */
  public record RecordDocument(
      String id,
      DocumentType docType,
      Integer groupIndex,
      String fileName,
      String mimeType,
      String sha256,
      DocumentStatus status,
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
      Form1View form1,
      Form2View form2,
      List<Form3EntryView> form3,
      List<RecordDocument> documents,
      List<RecordGeneratedDocument> generatedDocuments) {}

  /** The plaintext sensitive values returned by the explicit, audited reveal action (§6). */
  public record RevealedSensitive(Form1View form1, Form2View form2, List<Form3EntryView> form3) {}

  public record RouteToManagerResult(
      String employeeCode, EmployeeStatus status, String approvalRequestId, String managerName) {}
}
