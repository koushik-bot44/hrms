package com.ihrms.iclock;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the eSSL/ZKTeco ADMS ingest (prefix {@code app.iclock}). Bound automatically by
 * the {@code @ConfigurationPropertiesScan} on {@code ApiApplication} — no registration edit needed.
 * Kept separate from {@code AppProperties} deliberately: that is a record, so adding a component
 * there would change its canonical constructor signature and it also carries the secrets
 * {@code StartupSecretsGuard} reads.
 *
 * <p>{@link #enabled} gates PERSISTENCE ONLY. The controller and the capture filter register
 * unconditionally, so {@code /iclock} always answers {@code text/plain} and never falls through to
 * the JSON error machinery — a device pointed at a misconfigured instance gets a clean "OK" instead
 * of a JSON 404 retry storm. Note also that Flyway is unconditional, so the V41 tables exist
 * regardless of this flag.
 */
@ConfigurationProperties(prefix = "app.iclock")
public record IclockProperties(
    boolean enabled,
    int maxBodyBytes,
    int rateLimitPerMinute,
    String ackFormat,
    Options options) {

  /** Applies safe fallbacks so a blank or zeroed env var cannot produce a nonsensical config. */
  public IclockProperties {
    maxBodyBytes = maxBodyBytes > 0 ? maxBodyBytes : 262_144;
    rateLimitPerMinute = rateLimitPerMinute > 0 ? rateLimitPerMinute : 600;
    ackFormat = (ackFormat == null || ackFormat.isBlank()) ? "count" : ackFormat.trim();
    options = options != null ? options : Options.defaults();
  }

  /** True when the ATTLOG acknowledgement should be {@code OK: <n>} rather than a bare {@code OK}. */
  public boolean ackWithCount() {
    return "count".equalsIgnoreCase(ackFormat);
  }

  /**
   * The handshake options block the device parses to configure itself. {@code Realtime=1} is what
   * turns on live push rather than scheduled batch upload.
   *
   * <p>Every key is config-driven precisely because we do not yet know which ones this firmware
   * honours — they can be tuned against a real terminal without a code change. Models differ in
   * which keys they parse and in what the {@code TransFlag} bits mean.
   */
  public record Options(
      int errorDelay,
      int delay,
      String transTimes,
      int transInterval,
      String transFlag,
      String timeZone,
      int realtime,
      int encrypt) {

    public static Options defaults() {
      return new Options(30, 10, "00:00;14:05", 1, "1111000000", "5.5", 1, 0);
    }
  }
}
