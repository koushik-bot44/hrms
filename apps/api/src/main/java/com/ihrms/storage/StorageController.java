package com.ihrms.storage;

import com.ihrms.config.AppProperties;
import com.ihrms.storage.BlobTokenCodec.Parsed;
import com.ihrms.storage.DocumentBlobStore.Blob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public blob endpoint for {@code STORAGE_DRIVER=db} — the DB equivalent of an S3 presigned PUT/GET.
 * Authorization is the encrypted, single-purpose, time-limited token in the path (not the session),
 * exactly like a presigned URL, so this is permitted without a JWT in {@code SecurityConfig}. The
 * underlying document access is still audited at the onboarding/review service layer.
 */
@RestController
@RequestMapping("/storage/blobs")
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "db")
public class StorageController {

  private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

  private final DocumentBlobStore blobs;
  private final BlobTokenCodec codec;
  private final long maxBytes;

  public StorageController(DocumentBlobStore blobs, BlobTokenCodec codec, AppProperties props) {
    this.blobs = blobs;
    this.codec = codec;
    this.maxBytes =
        props.storage() != null && props.storage().maxBytes() > 0
            ? props.storage().maxBytes()
            : DEFAULT_MAX_BYTES;
  }

  /** Upload: the client PUTs the raw file bytes (any content type) under a PUT-scoped token. */
  @PutMapping("/{token}")
  public ResponseEntity<Void> upload(
      @PathVariable String token, @RequestBody(required = false) byte[] body) {
    Parsed t = codec.verify(token, "PUT");
    if (body == null || body.length == 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty upload");
    }
    if (body.length > maxBytes) {
      throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "File is too large");
    }
    blobs.save(t.storageKey(), body, t.contentType());
    return ResponseEntity.ok().build();
  }

  /** Download/view: stream the stored bytes back under a GET-scoped token. */
  @GetMapping("/{token}")
  public ResponseEntity<byte[]> download(@PathVariable String token) {
    Parsed t = codec.verify(token, "GET");
    Blob blob =
        blobs
            .find(t.storageKey())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "File not found"));
    MediaType type =
        blob.contentType() != null
            ? MediaType.parseMediaType(blob.contentType())
            : MediaType.APPLICATION_OCTET_STREAM;
    return ResponseEntity.ok()
        .contentType(type)
        .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .body(blob.data());
  }
}
