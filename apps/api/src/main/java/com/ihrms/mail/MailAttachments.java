package com.ihrms.mail;

import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Limits + type validation for mail attachments (§8, Stage 4). The server is the authority: it validates
 * BOTH the declared content type AND the file extension, so a spoofed content type or a bypassed client
 * cannot smuggle an executable/script. Enforced identically at upload-url time and again on bind.
 */
@Component
public class MailAttachments {

  /** At most this many files per message. */
  public static final int MAX_PER_MESSAGE = 5;

  /** 10 MiB per file (matches the document {@code MAX_UPLOAD_BYTES}). */
  public static final long MAX_BYTES = 10L * 1024 * 1024;

  /** Presigned PUT / GET lifetimes (short-lived, §6). */
  public static final int UPLOAD_TTL_SECONDS = 300;
  public static final int DOWNLOAD_TTL_SECONDS = 60;

  /**
   * Allowed content types → the file extensions valid for each. Anything else — notably executables and
   * scripts (exe/sh/bat/js/jar/…) — is rejected because it is absent from this allowlist.
   */
  private static final Map<String, Set<String>> ALLOWED =
      Map.ofEntries(
          Map.entry("image/png", Set.of("png")),
          Map.entry("image/jpeg", Set.of("jpg", "jpeg")),
          Map.entry("image/gif", Set.of("gif")),
          Map.entry("image/webp", Set.of("webp")),
          Map.entry("application/pdf", Set.of("pdf")),
          Map.entry("text/plain", Set.of("txt")),
          Map.entry("text/csv", Set.of("csv")),
          Map.entry(
              "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
              Set.of("docx")),
          Map.entry(
              "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", Set.of("xlsx")),
          Map.entry(
              "application/vnd.openxmlformats-officedocument.presentationml.presentation",
              Set.of("pptx")),
          Map.entry("application/zip", Set.of("zip")),
          Map.entry("application/x-zip-compressed", Set.of("zip")));

  /**
   * Validate one file's declared metadata. Throws a 400/413/415 if the size, content type, or extension
   * is not allowed. The extension must match the declared content type — a {@code .exe} claiming to be a
   * PDF, or a real PDF renamed {@code .sh}, is rejected either way.
   */
  public String validate(String fileName, String contentType, long sizeBytes) {
    if (fileName == null || fileName.isBlank()) {
      throw bad("A file name is required");
    }
    if (sizeBytes <= 0) {
      throw bad("File is empty");
    }
    if (sizeBytes > MAX_BYTES) {
      throw new ResponseStatusException(
          HttpStatus.PAYLOAD_TOO_LARGE, "File is too large — the maximum is 10 MB");
    }
    String type = normalizeType(contentType);
    Set<String> extensions = ALLOWED.get(type);
    if (extensions == null) {
      throw new ResponseStatusException(
          HttpStatus.UNSUPPORTED_MEDIA_TYPE,
          "This file type is not allowed. Executables and scripts cannot be attached.");
    }
    String ext = extensionOf(fileName);
    if (!extensions.contains(ext)) {
      throw bad("The file name does not match its type (\"." + ext + "\" is not allowed here).");
    }
    return type;
  }

  /** The lower-cased content type without any parameters (e.g. strips {@code ; charset=utf-8}). */
  public static String normalizeType(String contentType) {
    if (contentType == null) {
      return "";
    }
    String t = contentType.trim().toLowerCase();
    int semi = t.indexOf(';');
    return semi >= 0 ? t.substring(0, semi).trim() : t;
  }

  private static String extensionOf(String fileName) {
    String name = fileName.trim().toLowerCase();
    int dot = name.lastIndexOf('.');
    return dot >= 0 && dot < name.length() - 1 ? name.substring(dot + 1) : "";
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
