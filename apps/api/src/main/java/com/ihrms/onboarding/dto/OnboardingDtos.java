package com.ihrms.onboarding.dto;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.enums.SectionStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** Employee onboarding request/response DTOs — JSON shapes match api-contract.md §3.6 exactly. */
public final class OnboardingDtos {

  /** 10 MiB, matching the shared MAX_UPLOAD_BYTES. */
  public static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

  /** Max files an employee may upload per document type, per section (e.g. PAN front + back). */
  public static final int MAX_DOCUMENTS_PER_TYPE = 2;

  private OnboardingDtos() {}

  public record SaveSectionRequest(@NotNull(message = "data is required") Map<String, Object> data) {}

  public record DocumentUploadRequest(
      @NotNull(message = "sectionKey is required") SectionKey sectionKey,
      @NotNull(message = "docType is required") DocumentType docType,
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

  public record ProfileSectionView(
      SectionKey key, Map<String, Object> data, SectionStatus status, String updatedAt) {}

  /** No {@code storageKey} — the raw key is never exposed (§6). */
  public record DocumentView(
      String id,
      SectionKey sectionKey,
      DocumentType docType,
      String fileName,
      String mimeType,
      String sha256,
      DocumentStatus status,
      String uploadedAt) {}

  public record OnboardingDashboard(
      String employeeCode,
      String email,
      EmployeeStatus status,
      List<ProfileSectionView> sections,
      List<DocumentView> documents) {}

  public record PresignedUpload(
      String documentId,
      String uploadUrl,
      String method,
      Map<String, String> headers,
      int expiresInSeconds) {}

  public record PresignedView(String url, int expiresInSeconds) {}
}
