package com.ihrms.onboarding.dto;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.GeneratedDocumentKind;
import com.ihrms.domain.enums.SectionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Employee onboarding request/response DTOs for the four-form stepper (§3.2). Save is lenient so
 * drafts round-trip; required-field completeness is enforced at submit. Sensitive values
 * ({@code offeredCtc}, {@code workingExperiences[].salaryCtc}, {@code panNumber},
 * {@code axisAccountNumber}) are returned in full on the employee's OWN dashboard and are encrypted
 * at rest; HR sees them masked (see review DTOs).
 */
public final class OnboardingDtos {

  /** 10 MiB, matching the shared MAX_UPLOAD_BYTES. */
  public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

  /** Upper bound across any slot (Aadhaar/PAN); see {@link #maxDocumentsForType}. */
  public static final int MAX_DOCUMENTS_PER_SLOT = 2;

  /** Aadhaar & PAN accept two files (front + back); every other document is a single file. */
  public static int maxDocumentsForType(DocumentType docType) {
    return docType == DocumentType.AADHAAR || docType == DocumentType.PAN
        ? MAX_DOCUMENTS_PER_SLOT
        : 1;
  }

  private OnboardingDtos() {}

  // --- Form 1: Personal Details ---------------------------------------------

  public record EducationalQualification(
      @Size(max = 200) String qualification,
      @Size(max = 200) String university,
      @Size(max = 20) String yearOfPassing,
      @Size(max = 20) String percentage) {}

  public record WorkingExperience(
      @Size(max = 200) String organization,
      @Size(max = 100) String period,
      @Size(max = 150) String designation,
      @Size(max = 60) String salaryCtc,
      @Size(max = 200) String reasonForLeaving) {}

  public record FamilyDetail(
      @Size(max = 150) String name,
      @Size(max = 10) String age,
      @Size(max = 60) String relation,
      @Size(max = 120) String occupation) {}

  public record CharacterReference(
      @Size(max = 150) String name,
      @Size(max = 250) String address,
      @Size(max = 30) String phone) {}

  public record Form1Request(
      @Size(max = 150) String name,
      @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}", message = "Use YYYY-MM-DD") String dateOfBirth,
      @Size(max = 180) String email,
      @Size(max = 30) String mobile,
      @Size(max = 150) String designation,
      @Size(max = 60) String offeredCtc,
      @Size(max = 300) String currentAddress,
      @Size(max = 300) String permanentAddress,
      // Relocated from Form 2 (presentation only — stored on form2_info exactly as before; PAN +
      // account number keep their encryption at rest and masked/audited-reveal handling).
      @Size(max = 30) String alternateNumber,
      @Size(max = 40) String vehicleNo2W4W,
      @Size(max = 20) String panNumber,
      @Size(max = 40) String axisAccountNumber,
      @Size(max = 40) String maritalStatus,
      @Size(max = 10) String bloodGroup,
      @Size(max = 150) String closestRelativeName,
      @Size(max = 30) String closestRelativePhone,
      @Size(max = 80) String city,
      @Size(max = 60) String relationship,
      @Size(max = 4000) String declaration,
      @Valid @Size(max = 20) List<EducationalQualification> educationalQualifications,
      @Valid @Size(max = 20) List<WorkingExperience> workingExperiences,
      @Valid @Size(max = 20) List<FamilyDetail> familyDetails,
      @Valid @Size(max = 20) List<CharacterReference> characterReferences) {}

  public record Form1View(
      String name,
      String dateOfBirth,
      String email,
      String mobile,
      String designation,
      String offeredCtc,
      String currentAddress,
      String permanentAddress,
      String alternateNumber,
      String vehicleNo2W4W,
      String panNumber,
      String axisAccountNumber,
      String maritalStatus,
      String bloodGroup,
      String closestRelativeName,
      String closestRelativePhone,
      String city,
      String relationship,
      String declaration,
      List<EducationalQualification> educationalQualifications,
      List<WorkingExperience> workingExperiences,
      List<FamilyDetail> familyDetails,
      List<CharacterReference> characterReferences,
      SectionStatus status,
      String revisionNote,
      String updatedAt) {}

  // --- Form 2: Employee Info ------------------------------------------------

  // alternate number, vehicle no, PAN, account number and the two addresses moved to Form 1's
  // PRESENTATION (§3.2) — their storage stays on form2_info and is written through by Form 1.
  public record Form2Request(
      @Size(max = 150) String fullName,
      @Size(max = 150) String fatherName,
      @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}", message = "Use YYYY-MM-DD") String dateOfBirth,
      @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}", message = "Use YYYY-MM-DD") String dateOfJoining,
      @Size(max = 10) String bloodGroup,
      @Size(max = 30) String mobile,
      @Size(max = 180) String officialEmail,
      @Size(max = 180) String personalEmail,
      @Size(max = 150) String designation,
      @Size(max = 60) String documentSubmitted) {}

  /** {@code employeeId} is the system-assigned code (null until Manager approval); read-only. */
  public record Form2View(
      String fullName,
      String fatherName,
      String employeeId,
      String dateOfBirth,
      String dateOfJoining,
      String bloodGroup,
      String mobile,
      String officialEmail,
      String personalEmail,
      String designation,
      String sparkId,
      String documentSubmitted,
      SectionStatus status,
      String revisionNote,
      String updatedAt) {}

  // --- Form 3: Previous Employment (repeatable) -----------------------------

  public record Form3Entry(
      @Size(max = 200) String companyName,
      @Size(max = 300) String companyAddress,
      @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}", message = "Use YYYY-MM-DD") String dateOfJoining,
      @Pattern(regexp = "|\\d{4}-\\d{2}-\\d{2}", message = "Use YYYY-MM-DD") String dateOfRelieving,
      @Size(max = 150) String designation,
      @Size(max = 60) String lastDrawnSalary,
      @Size(max = 60) String jobType,
      @Size(max = 250) String reasonForLeaving,
      @Size(max = 150) String reportingTo,
      @Size(max = 60) String roContact,
      @Size(max = 150) String hrNameContact) {}

  public record Form3Request(@Valid @Size(max = 20) List<Form3Entry> entries) {}

  public record Form3EntryView(
      String id,
      String companyName,
      String companyAddress,
      String dateOfJoining,
      String dateOfRelieving,
      String designation,
      String lastDrawnSalary,
      String jobType,
      String reasonForLeaving,
      String reportingTo,
      String roContact,
      String hrNameContact,
      SectionStatus status,
      String revisionNote) {}

  // --- Form 4: Documents (uploads) ------------------------------------------

  public record DocumentUploadRequest(
      @NotNull(message = "docType is required") DocumentType docType,
      @Max(value = 4, message = "groupIndex must be 1..4") Integer groupIndex,
      @NotBlank(message = "File name is required") @Size(max = 255, message = "File name is too long")
          String fileName,
      @NotNull(message = "mimeType is required")
          @Pattern(
              regexp = "application/pdf|image/png|image/jpeg",
              message = "Unsupported file type — use PDF, PNG, or JPEG")
          String mimeType,
      @Positive(message = "sizeBytes must be positive")
          @Max(value = MAX_UPLOAD_BYTES, message = "File is too large")
          long sizeBytes) {}

  /** Re-upload a document HR sent back for revision (§3.3): the slot is fixed by the existing doc. */
  public record DocumentReviseRequest(
      @NotBlank(message = "File name is required") @Size(max = 255, message = "File name is too long")
          String fileName,
      @NotNull(message = "mimeType is required")
          @Pattern(
              regexp = "application/pdf|image/png|image/jpeg",
              message = "Unsupported file type — use PDF, PNG, or JPEG")
          String mimeType,
      @Positive(message = "sizeBytes must be positive")
          @Max(value = MAX_UPLOAD_BYTES, message = "File is too large")
          long sizeBytes) {}

  /** No {@code storageKey} — the raw key is never exposed (§6). */
  public record DocumentView(
      String id,
      DocumentType docType,
      Integer groupIndex,
      String fileName,
      String mimeType,
      String sha256,
      DocumentStatus status,
      String revisionNote,
      String uploadedAt) {}

  // --- Signature ------------------------------------------------------------

  /** The captured e-signature at final submit: a {@code data:} image URL, drawn or typed. */
  public record SignatureRequest(
      @NotBlank(message = "Signature image is required")
          @Pattern(regexp = "data:image/(png|jpeg);base64,.+", message = "Signature must be a PNG/JPEG data URL")
          String imageDataUrl,
      @NotNull @Pattern(regexp = "DRAWN|TYPED") String type) {}

  public record SignatureView(String type, String signedAt) {}

  // --- Generated documents --------------------------------------------------

  public record GeneratedDocumentView(
      String id, GeneratedDocumentKind kind, String fileName, String sha256, String generatedAt) {}

  // --- Dashboard + presign envelopes ----------------------------------------

  public record OnboardingDashboard(
      String employeeCode,
      String email,
      String fullName,
      String designation,
      EmployeeStatus status,
      Form1View form1,
      Form2View form2,
      List<Form3EntryView> form3,
      List<DocumentView> documents,
      SignatureView signature,
      List<GeneratedDocumentView> generatedDocuments) {}

  public record PresignedUpload(
      String documentId,
      String uploadUrl,
      String method,
      java.util.Map<String, String> headers,
      int expiresInSeconds) {}

  public record PresignedView(String url, int expiresInSeconds) {}
}
