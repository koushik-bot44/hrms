package com.ihrms.storage;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Storage facade used by onboarding/review. Delegates the presign/read handshake to the active
 * {@link StorageBackend} ({@code s3} or {@code db}, chosen by {@code app.storage.driver}) while
 * owning the backend-agnostic key layout. Files are reached only via short-lived presigned URLs;
 * raw storage keys are never returned to clients (§6).
 */
@Service
public class StorageService {

  private static final Logger log = LoggerFactory.getLogger(StorageService.class);

  private final StorageBackend backend;

  public StorageService(StorageBackend backend) {
    this.backend = backend;
  }

  public boolean isConfigured() {
    return backend.isConfigured();
  }

  /** A unique object key namespaced under the employee's record (never exposed to clients). */
  public String buildKey(String companyId, String employeeId, String sectionKey, String fileName) {
    String safe = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (safe.length() > 120) {
      safe = safe.substring(0, 120);
    }
    return "companies/"
        + companyId
        + "/employees/"
        + employeeId
        + "/"
        + sectionKey
        + "/"
        + UUID.randomUUID()
        + "-"
        + safe;
  }

  /**
   * The STABLE letterhead keys (§3.5, document model). One letterhead per company under
   * {@code companies/{cid}/branding/} — a replacement overwrites the SAME keys, so there is never a second
   * object to orphan. {@code leaf} is {@code "letterhead.pdf"} (the normalized single-page PDF stamped behind
   * documents), {@code "letterhead-original"} (the uploaded PDF/Word — ext-less, its type tracked on the row),
   * or {@code "letterhead-preview.png"} (the rasterized first page for the margin editor).
   */
  public String buildLetterheadKey(String companyId, String leaf) {
    return "companies/" + companyId + "/branding/" + leaf;
  }

  /** A unique object key for a mail attachment, namespaced under its uploader (never exposed, §8). */
  public String buildMailAttachmentKey(String uploaderUserId, String fileName) {
    String safe = fileName.replaceAll("[^a-zA-Z0-9._-]", "_");
    if (safe.length() > 120) {
      safe = safe.substring(0, 120);
    }
    return "mail/attachments/" + uploaderUserId + "/" + UUID.randomUUID() + "-" + safe;
  }

  public String presignedPutUrl(String key, String contentType, int expiresInSeconds) {
    return backend.presignedPutUrl(key, contentType, expiresInSeconds);
  }

  public String presignedGetUrl(String key, int expiresInSeconds) {
    return backend.presignedGetUrl(key, expiresInSeconds);
  }

  /** Server-side write of bytes (for generated PDFs + the captured signature image). */
  public void putObject(String key, byte[] bytes, String contentType) {
    backend.putObject(key, bytes, contentType);
  }

  /** Server-side read of the stored bytes (for hashing). */
  public byte[] getObjectBytes(String key) {
    return backend.getObjectBytes(key);
  }

  /** Remove the stored object (used when an employee deletes a document). */
  public void delete(String key) {
    backend.delete(key);
  }

  /**
   * Best-effort bulk delete of stored objects (used post-commit when a company is purged). Never
   * throws: any objects that could not be removed are logged as orphans for later cleanup.
   */
  public void deleteQuietly(Collection<String> keys) {
    if (keys == null || keys.isEmpty()) {
      return;
    }
    try {
      List<String> failed = backend.deleteObjects(keys);
      if (!failed.isEmpty()) {
        log.warn(
            "Storage purge: {} of {} object(s) could not be deleted and are orphaned: {}",
            failed.size(), keys.size(), failed);
      }
    } catch (RuntimeException e) {
      log.error("Storage purge failed for {} object(s); they are orphaned", keys.size(), e);
    }
  }
}
