package com.ihrms.storage;

import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads/writes document bytes in the {@code document_blobs} table for {@code STORAGE_DRIVER=db}.
 * Deliberately uses {@link JdbcTemplate} (raw {@code bytea}) rather than a JPA entity so Hibernate's
 * boot-time {@code ddl-auto=validate} never sees the binary column — avoiding the {@code byte[]} ->
 * {@code bytea} JDBC type-code mismatch that would otherwise break startup. Only active in db mode.
 */
@Component
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "db")
public class DocumentBlobStore {

  private final JdbcTemplate jdbc;

  public DocumentBlobStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Insert (or replace, on retry) the bytes for a storage key. */
  public void save(String storageKey, byte[] data, String contentType) {
    jdbc.update(
        "INSERT INTO \"document_blobs\" (\"storageKey\",\"data\",\"contentType\",\"sizeBytes\",\"createdAt\")"
            + " VALUES (?,?,?,?,CURRENT_TIMESTAMP)"
            + " ON CONFLICT (\"storageKey\") DO UPDATE SET"
            + " \"data\"=EXCLUDED.\"data\", \"contentType\"=EXCLUDED.\"contentType\","
            + " \"sizeBytes\"=EXCLUDED.\"sizeBytes\"",
        storageKey,
        data,
        contentType,
        data.length);
  }

  /** Fetch the bytes + content type for a storage key, if present. */
  public Optional<Blob> find(String storageKey) {
    return jdbc.query(
        "SELECT \"data\",\"contentType\" FROM \"document_blobs\" WHERE \"storageKey\"=?",
        rs ->
            rs.next()
                ? Optional.of(new Blob(rs.getBytes("data"), rs.getString("contentType")))
                : Optional.empty(),
        storageKey);
  }

  public record Blob(byte[] data, String contentType) {}
}
