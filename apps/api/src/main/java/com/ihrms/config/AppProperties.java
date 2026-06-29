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
    Mail mail,
    S3 s3) {

  public record Mail(String host, int port, String username, String password, String from) {}

  public record S3(
      String endpoint,
      String region,
      String bucket,
      String accessKeyId,
      String secretAccessKey,
      boolean forcePathStyle) {}

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
