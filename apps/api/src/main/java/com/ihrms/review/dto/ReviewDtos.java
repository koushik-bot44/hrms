package com.ihrms.review.dto;

import com.ihrms.domain.enums.DocumentStatus;
import com.ihrms.domain.enums.DocumentType;
import com.ihrms.domain.enums.EmployeeStatus;
import com.ihrms.domain.enums.SectionKey;
import com.ihrms.domain.enums.SectionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * HR verification & routing DTOs (ARCHITECTURE.md §3.3/§3.4). Records are springdoc-visible
 * (referenced by ReviewController), so /v3/api-docs reflects them and the web types regenerate.
 */
public final class ReviewDtos {

  private ReviewDtos() {}

  /** Verify or reject a section/document; the optional reason is recorded in the audit trail. */
  public record ReviewRequest(
      @NotBlank
          @Pattern(regexp = "VERIFIED|REJECTED", message = "decision must be VERIFIED or REJECTED")
          @Schema(allowableValues = {"VERIFIED", "REJECTED"})
          String decision,
      @Size(max = 500, message = "Reason is too long") String reason) {}

  /** Route the reviewed record to the team's Manager for approval. */
  public record RouteToManagerRequest(
      @Size(max = 500, message = "Note is too long") String note) {}

  public record RecordSection(
      SectionKey key, Map<String, Object> data, SectionStatus status, String updatedAt) {}

  /** {@code viewUrl} is a short-lived presigned GET — the raw storage key is never exposed (§6). */
  public record RecordDocument(
      String id,
      SectionKey sectionKey,
      DocumentType docType,
      String fileName,
      String mimeType,
      String sha256,
      DocumentStatus status,
      String uploadedAt,
      String viewUrl) {}

  /** The full employee record HR reviews; {@code reviewComplete} gates routing to the Manager. */
  public record EmployeeRecordView(
      String employeeCode,
      String email,
      EmployeeStatus status,
      boolean reviewComplete,
      List<RecordSection> sections,
      List<RecordDocument> documents) {}

  public record RouteToManagerResult(
      String employeeCode, EmployeeStatus status, String approvalRequestId, String managerName) {}
}
