package com.ihrms.support;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hex SHA-256 of file bytes (document integrity, §4). */
public final class Hashing {

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private Hashing() {}

  public static String sha256Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      char[] out = new char[digest.length * 2];
      for (int i = 0; i < digest.length; i++) {
        int b = digest[i] & 0xff;
        out[i * 2] = HEX[b >>> 4];
        out[i * 2 + 1] = HEX[b & 0x0f];
      }
      return new String(out);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
