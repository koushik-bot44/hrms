package com.ihrms.storage;

import com.ihrms.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mints and verifies opaque, tamper-proof, time-limited tokens that stand in for S3 presigned URLs
 * when {@code STORAGE_DRIVER=db}. The token is {@code base64url(iv || AES-GCM(payload))} where the
 * payload is {@code storageKey\nop\ncontentType\nexp}. AES-GCM (with a key derived from the app's
 * JWT secret) makes the token unforgeable AND keeps the raw storageKey encrypted, so it is never
 * exposed to clients (§6). Only active in the {@code db} storage profile.
 */
@Component
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "db")
public class BlobTokenCodec {

  private static final int IV_LEN = 12;
  private static final int TAG_BITS = 128;

  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();

  public BlobTokenCodec(AppProperties props) {
    // Derive a stable 256-bit AES key from the app secret (already required & high-entropy in prod).
    this.key = new SecretKeySpec(sha256(props.jwtSecret().getBytes(StandardCharsets.UTF_8)), "AES");
  }

  /** Encrypt {storageKey, op, contentType, expiry} into a URL-safe token. */
  public String encode(String storageKey, String op, String contentType, int ttlSeconds) {
    long exp = Instant.now().getEpochSecond() + ttlSeconds;
    String payload =
        storageKey + "\n" + op + "\n" + (contentType == null ? "" : contentType) + "\n" + exp;
    byte[] iv = new byte[IV_LEN];
    random.nextBytes(iv);
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("Storage token encryption failed", e);
    }
  }

  /** Decode + authenticate a token, enforcing the expected op and expiry. */
  public Parsed verify(String token, String expectedOp) {
    Parsed p = decode(token);
    if (!expectedOp.equals(p.op())) {
      throw forbidden();
    }
    if (Instant.now().getEpochSecond() > p.exp()) {
      throw new ResponseStatusException(HttpStatus.GONE, "This link has expired");
    }
    return p;
  }

  private Parsed decode(String token) {
    byte[] raw;
    try {
      raw = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      throw forbidden();
    }
    if (raw.length <= IV_LEN) {
      throw forbidden();
    }
    byte[] iv = Arrays.copyOfRange(raw, 0, IV_LEN);
    byte[] ct = Arrays.copyOfRange(raw, IV_LEN, raw.length);
    String payload;
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
      payload = new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      throw forbidden();
    }
    String[] parts = payload.split("\n", -1);
    if (parts.length != 4) {
      throw forbidden();
    }
    long exp;
    try {
      exp = Long.parseLong(parts[3]);
    } catch (NumberFormatException e) {
      throw forbidden();
    }
    return new Parsed(parts[0], parts[1], parts[2].isEmpty() ? null : parts[2], exp);
  }

  public record Parsed(String storageKey, String op, String contentType, long exp) {}

  private static ResponseStatusException forbidden() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid or malformed storage token");
  }

  private static byte[] sha256(byte[] in) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(in);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
