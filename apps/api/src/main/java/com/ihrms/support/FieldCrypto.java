package com.ihrms.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Field-level encryption for sensitive PII/financial values (§6): {@code offeredCtc},
 * {@code form3.lastDrawnSalary}, {@code panNumber}, {@code axisAccountNumber}, {@code aadhaarNumber}.
 * AES-256-GCM with a random 12-byte IV per value; the stored form is
 * {@code enc:v1:base64(iv || ciphertext+tag)}.
 *
 * <p>The 256-bit key is derived (SHA-256) from {@code FIELD_ENC_KEY} so any passphrase works; a
 * documented dev default is used when unset (production MUST set a real key, like JWT_SECRET). The
 * holder self-initialises from the environment on first use so it is safe to call before the Spring
 * context wires {@link #configure(String)} (e.g. inside a JPA {@code AttributeConverter}).
 *
 * <p>{@link #decrypt(String)} passes through any value lacking the {@code enc:v1:} prefix, so seeds,
 * tests, and legacy plaintext round-trip unchanged. {@link #encryptWithKey}/{@link #decryptWithKey}
 * operate under an EXPLICIT passphrase — used by the one-time re-key migration (FieldKeyReencryptor)
 * to move values off a previous key onto the current {@code FIELD_ENC_KEY}.
 */
public final class FieldCrypto {

  public static final String PREFIX = "enc:v1:";

  /**
   * The committed dev-default passphrase (mirrors {@code application.yml}). It is NOT a secret — it ships in
   * the repo — and is exposed so the one-time re-key migration can decrypt values written while
   * {@code FIELD_ENC_KEY} was unset (the exact situation the prod boot guard surfaced).
   */
  public static final String DEV_DEFAULT_KEY = "dev-insecure-field-enc-key-change-me-please-0001";

  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private static final SecureRandom RANDOM = new SecureRandom();
  private static volatile SecretKeySpec key;

  private FieldCrypto() {}

  /** Wire the key from configuration (called once at startup); re-initialises the derived key. */
  public static void configure(String rawKey) {
    key = deriveKey(rawKey);
  }

  /** Encrypt a value under the ACTIVE key; null/blank pass through unchanged (nothing to protect). */
  public static String encrypt(String plaintext) {
    return encryptWith(plaintext, activeKey());
  }

  /** Decrypt a stored value under the ACTIVE key; anything without the {@code enc:v1:} prefix is verbatim. */
  public static String decrypt(String stored) {
    return decryptWith(stored, activeKey());
  }

  /** Encrypt under an EXPLICIT passphrase (re-key migration). */
  public static String encryptWithKey(String plaintext, String passphrase) {
    return encryptWith(plaintext, deriveKey(passphrase));
  }

  /** Decrypt under an EXPLICIT passphrase (re-key migration); throws if the key/tag doesn't match. */
  public static String decryptWithKey(String stored, String passphrase) {
    return decryptWith(stored, deriveKey(passphrase));
  }

  public static boolean isEncrypted(String value) {
    return value != null && value.startsWith(PREFIX);
  }

  private static String encryptWith(String plaintext, SecretKeySpec k) {
    if (plaintext == null || plaintext.isEmpty()) {
      return plaintext;
    }
    if (plaintext.startsWith(PREFIX)) {
      return plaintext; // already encrypted — never double-wrap
    }
    try {
      byte[] iv = new byte[IV_BYTES];
      RANDOM.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
      byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      byte[] out = new byte[iv.length + ct.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(ct, 0, out, iv.length, ct.length);
      return PREFIX + Base64.getEncoder().encodeToString(out);
    } catch (Exception e) {
      throw new IllegalStateException("Field encryption failed", e);
    }
  }

  private static String decryptWith(String stored, SecretKeySpec k) {
    if (stored == null || !stored.startsWith(PREFIX)) {
      return stored;
    }
    try {
      byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
      byte[] iv = new byte[IV_BYTES];
      System.arraycopy(raw, 0, iv, 0, IV_BYTES);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(TAG_BITS, iv));
      byte[] pt = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
      return new String(pt, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Field decryption failed", e);
    }
  }

  private static SecretKeySpec activeKey() {
    SecretKeySpec k = key;
    if (k == null) {
      synchronized (FieldCrypto.class) {
        if (key == null) {
          String env = System.getenv("FIELD_ENC_KEY");
          key = deriveKey(env == null || env.isBlank() ? DEV_DEFAULT_KEY : env);
        }
        k = key;
      }
    }
    return k;
  }

  private static SecretKeySpec deriveKey(String raw) {
    String material = raw == null || raw.isBlank() ? DEV_DEFAULT_KEY : raw;
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
      return new SecretKeySpec(digest, "AES");
    } catch (Exception e) {
      throw new IllegalStateException("Could not derive field-encryption key", e);
    }
  }
}
