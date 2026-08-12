package com.ihrms.companies.dto;

import java.util.Map;

/** Per-company letterhead DTOs (§3.5). SUPER_ADMIN uploads a HEADER and/or FOOTER band image per company. */
public final class LetterheadDtos {
  private LetterheadDtos() {}

  /** Client-declared type + size for the presigned upload (validated server-side; re-validated on confirm). */
  public record LetterheadUploadRequest(String contentType, long sizeBytes) {}

  /** The presigned PUT handshake (same shape as the mail-attachment upload). */
  public record LetterheadUpload(
      String uploadUrl, String method, Map<String, String> headers, int expiresInSeconds, String part) {}

  /** One stored part's metadata + a short-lived presigned preview URL (null when storage can't presign). */
  public record LetterheadPartView(int width, int height, String contentType, String previewUrl) {}

  /** The company's current letterhead: header/footer parts (null when unset) + when it last changed. */
  public record LetterheadView(LetterheadPartView header, LetterheadPartView footer, String updatedAt) {}
}
