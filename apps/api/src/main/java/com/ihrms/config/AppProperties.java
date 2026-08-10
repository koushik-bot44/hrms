package com.ihrms.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Type-safe binding of the application's environment configuration (prefix {@code app}
 * in application.yml, sourced from env vars). Mirrors the env the archived NestJS API
 * read, so deployments keep the same variable names.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
    String corsOrigins,
    String webAppUrl,
    String jwtSecret,
    String refreshSecret,
    String accessTokenTtl,
    String refreshTokenTtl,
    String refreshCookieName,
    int otpTtl,
    String fieldEncKey,
    Mail mail,
    S3 s3,
    Storage storage,
    Vapid vapid) {

  public record Mail(
      String host,
      int port,
      String username,
      String password,
      String from,
      String replyTo,
      String contactInbox) {}

  /**
   * Web Push (VAPID) keys — an EC P-256 keypair (base64url) + a {@code mailto:} subject. SECRETS: set via
   * {@code VAPID_PUBLIC_KEY} / {@code VAPID_PRIVATE_KEY} / {@code VAPID_SUBJECT} in the environment; never
   * committed. Blank => Web Push is disabled in dev and refused at startup in prod (see PushService).
   */
  public record Vapid(String publicKey, String privateKey, String subject) {
    public boolean isConfigured() {
      return notBlank(publicKey) && notBlank(privateKey) && notBlank(subject);
    }

    private static boolean notBlank(String s) {
      return s != null && !s.isBlank();
    }
  }

  public record S3(
      String endpoint,
      String region,
      String bucket,
      String accessKeyId,
      String secretAccessKey,
      boolean forcePathStyle) {}

  /** Storage backend selection: {@code driver} = {@code s3} (default) or {@code db}. */
  public record Storage(String driver, String publicBaseUrl, long maxBytes) {}

  /** CORS_ORIGINS as a trimmed, non-empty list. */
  public List<String> corsOriginList() {
    if (corsOrigins == null || corsOrigins.isBlank()) {
      return List.of();
    }
    return Arrays.stream(corsOrigins.split(","))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }
}
