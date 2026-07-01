package com.ihrms.storage;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Storage facade used by onboarding/review. Delegates the presign/read handshake to the active
 * {@link StorageBackend} ({@code s3} or {@code db}, chosen by {@code app.storage.driver}) while
 * owning the backend-agnostic key layout. Files are reached only via short-lived presigned URLs;
 * raw storage keys are never returned to clients (§6).
 */
@Service
public class StorageService {

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
}
