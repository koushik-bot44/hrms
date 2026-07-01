package com.ihrms.storage;

import com.ihrms.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Stores document bytes in Postgres ({@code document_blobs}) instead of object storage — selected
 * with {@code STORAGE_DRIVER=db} so a deployment needs no external bucket. "Presigned" URLs are the
 * API's own {@code /storage/blobs/{token}} endpoint, where the token is an encrypted, time-limited
 * {@link BlobTokenCodec} reference to the storageKey (which stays server-side). The absolute base
 * URL is taken from {@code STORAGE_PUBLIC_URL} when set, otherwise derived from the current request
 * (honouring {@code X-Forwarded-*} via {@code server.forward-headers-strategy}).
 */
@Component
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "db", matchIfMissing = true)
public class DbStorageBackend implements StorageBackend {

  private final DocumentBlobStore blobs;
  private final BlobTokenCodec codec;
  private final String configuredBaseUrl;

  public DbStorageBackend(DocumentBlobStore blobs, BlobTokenCodec codec, AppProperties props) {
    this.blobs = blobs;
    this.codec = codec;
    this.configuredBaseUrl = props.storage() == null ? null : props.storage().publicBaseUrl();
  }

  @Override
  public boolean isConfigured() {
    return true;
  }

  @Override
  public String presignedPutUrl(String key, String contentType, int expiresInSeconds) {
    return baseUrl() + "/storage/blobs/" + codec.encode(key, "PUT", contentType, expiresInSeconds);
  }

  @Override
  public String presignedGetUrl(String key, int expiresInSeconds) {
    return baseUrl() + "/storage/blobs/" + codec.encode(key, "GET", null, expiresInSeconds);
  }

  @Override
  public void putObject(String key, byte[] bytes, String contentType) {
    blobs.save(key, bytes, contentType);
  }

  @Override
  public byte[] getObjectBytes(String key) {
    return blobs
        .find(key)
        .map(DocumentBlobStore.Blob::data)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Stored file not found"));
  }

  @Override
  public void delete(String key) {
    blobs.delete(key);
  }

  private String baseUrl() {
    if (configuredBaseUrl != null && !configuredBaseUrl.isBlank()) {
      return stripTrailingSlash(configuredBaseUrl.trim());
    }
    try {
      return stripTrailingSlash(
          ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Storage base URL unavailable — set STORAGE_PUBLIC_URL");
    }
  }

  private static String stripTrailingSlash(String s) {
    return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
  }
}
