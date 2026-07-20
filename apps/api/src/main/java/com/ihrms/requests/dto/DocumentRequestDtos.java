package com.ihrms.requests.dto;

import com.ihrms.domain.enums.RequestStatus;
import com.ihrms.domain.enums.RequestType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * HR/Accounts document-request DTOs (§8d). Timestamps are ISO strings. Fulfilment files are exposed only as
 * metadata (id/name/type/size) — the raw storage key never leaves the server.
 */
public final class DocumentRequestDtos {

  private DocumentRequestDtos() {}

  /** Employee submits a request. The note (e.g. the period) is optional. */
  public record SubmitDocumentRequest(
      @NotNull(message = "A request type is required") RequestType requestType,
      @Size(max = 2000) String note) {}

  /** The Accountant asks for an upload URL for one fulfilment file (validated server-side). */
  public record UploadDocumentRequest(
      @NotBlank(message = "A file name is required") String fileName,
      @NotBlank(message = "A content type is required") String contentType,
      @Positive(message = "A positive size is required") long sizeBytes) {}

  /** The Accountant resolves the request, binding one or more just-uploaded files. */
  public record ResolveRequest(
      @NotNull(message = "At least one document is required") List<String> documentIds,
      @Size(max = 2000) String note) {}

  /** Fulfilment-file metadata (never the storage key). */
  public record RequestDocumentView(
      String id, String fileName, String contentType, long sizeBytes, String createdAt) {}

  /** The employee's own request (it's theirs — no employee identity needed). */
  public record DocumentRequestView(
      String id,
      RequestType requestType,
      String note,
      RequestStatus status,
      String resolveNote,
      String pickedUpAt,
      String resolvedAt,
      String createdAt,
      List<RequestDocumentView> documents) {}

  /** A row in the Accountant's queue — carries the employee's identity + their team. */
  public record TeamRequestRow(
      String id,
      String employeeId,
      String employeeCode,
      String employeeName,
      String teamName,
      RequestType requestType,
      String note,
      RequestStatus status,
      String resolveNote,
      String pickedUpAt,
      String resolvedAt,
      String createdAt,
      List<RequestDocumentView> documents) {}

  /** The presigned PUT envelope for a fulfilment upload. */
  public record RequestUpload(
      String documentId,
      String uploadUrl,
      String method,
      Map<String, String> headers,
      int expiresInSeconds) {}

  public record PresignedView(String url, int expiresInSeconds) {}

  public record MyRequestsPage(
      List<DocumentRequestView> content, int page, int size, long totalElements, int totalPages) {}

  public record TeamRequestsPage(
      List<TeamRequestRow> content, int page, int size, long totalElements, int totalPages) {}
}
