package com.ihrms.support;

import java.security.SecureRandom;
import java.util.Base64;

/** Generates initial staff credentials (12 random bytes, base64url) — matches the archived API. */
public final class TempPasswords {

  private static final SecureRandom RANDOM = new SecureRandom();

  private TempPasswords() {}

  public static String generate() {
    byte[] bytes = new byte[12];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
