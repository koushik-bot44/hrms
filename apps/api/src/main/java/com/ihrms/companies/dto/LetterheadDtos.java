package com.ihrms.companies.dto;

import java.util.Map;

/**
 * Per-company letterhead DTOs (§3.5, document model). The SUPER_ADMIN uploads ONE letterhead file (PDF, or
 * Word converted to PDF); its first page becomes the page background of every generated pipeline document, and
 * a margin box (points) sets where content may sit.
 */
public final class LetterheadDtos {
  private LetterheadDtos() {}

  /** Client-declared type + size for the presigned upload (validated server-side; re-validated on confirm). */
  public record LetterheadUploadRequest(String contentType, long sizeBytes) {}

  /** The presigned PUT handshake (same shape as the mail-attachment upload). */
  public record LetterheadUpload(
      String uploadUrl, String method, Map<String, String> headers, int expiresInSeconds) {}

  /** Save the content margin box (points from each page edge). All four are draggable in the editor. */
  public record MarginsRequest(Double topPt, Double bottomPt, Double leftPt, Double rightPt) {}

  /**
   * The company's current letterhead. {@code present} is false when unset (documents render plain); when true,
   * the page size + margin box (points) + a short-lived presigned preview of the first page drive the editor.
   * {@code wordConversionAvailable} tells the UI whether it may offer Word upload on this server.
   */
  public record LetterheadView(
      boolean present,
      Double pageWidthPt,
      Double pageHeightPt,
      Double marginTopPt,
      Double marginBottomPt,
      Double marginLeftPt,
      Double marginRightPt,
      String originalType,
      String previewUrl,
      String updatedAt,
      boolean wordConversionAvailable) {}
}
