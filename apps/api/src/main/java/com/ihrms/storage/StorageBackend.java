package com.ihrms.storage;

/**
 * Pluggable storage backend behind {@link StorageService}. One implementation is active per
 * deployment, chosen by {@code app.storage.driver}: {@code s3} (object storage, default) or
 * {@code db} (Postgres BYTEA). Both expose the same presigned-URL handshake so the HTTP contract
 * and the frontend are identical regardless of where bytes live.
 */
public interface StorageBackend {

  /** Whether uploads can be served; false makes the upload endpoints fail with 503. */
  boolean isConfigured();

  /** A URL the client PUTs the file bytes to (with {@code Content-Type}), valid for the TTL. */
  String presignedPutUrl(String key, String contentType, int expiresInSeconds);

  /** A short-lived URL the client GETs to view/download the stored file. */
  String presignedGetUrl(String key, int expiresInSeconds);

  /** Server-side read of the stored bytes (for hashing on confirm). */
  byte[] getObjectBytes(String key);
}
