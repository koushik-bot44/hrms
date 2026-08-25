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
    String captureMode,
    Options options) {

  /** Applies safe fallbacks so a blank or zeroed env var cannot produce a nonsensical config. */
  public IclockProperties {
    maxBodyBytes = maxBodyBytes > 0 ? maxBodyBytes : 262_144;
    rateLimitPerMinute = rateLimitPerMinute > 0 ? rateLimitPerMinute : 600;
    ackFormat = (ackFormat == null || ackFormat.isBlank()) ? "count" : ackFormat.trim();
    captureMode = (captureMode == null || captureMode.isBlank()) ? "full" : captureMode.trim();
    options = options != null ? options : Options.defaults();
  }

  /** True when the ATTLOG acknowledgement should be {@code OK: <n>} rather than a bare {@code OK}. */
  public boolean ackWithCount() {
    return "count".equalsIgnoreCase(ackFormat);
  }

  /**
   * True in {@code lean} capture mode, which drops the stored body for mapped, successful
   * {@code getrequest} polls only — every other request stays fully captured. Intended for after live
   * push is verified, when the poll stream is pure noise.
   *
   * <p>Be aware this saves less than it sounds: a {@code getrequest} poll carries an EMPTY body
   * already, so the row itself (not its body) is the volume. Retention/pruning is the real lever.
   */
  public boolean leanCapture() {
    return "lean".equalsIgnoreCase(captureMode);
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
      int encrypt,
      String serverVer) {

    public static Options defaults() {
      // Delay=30 matches the observed poll cadence of the ZAM180 / pushver 2.4.1 firmware.
      //
      // TimeZone MUST be an INTEGER OFFSET IN MINUTES. 330 = +05:30 (Asia/Kolkata).
      // This was learned the hard way: an earlier default of "5.5" (hours) was silently truncated
      // to 5 by the firmware, leaving every terminal that handshook 30 minutes SLOW and stamping
      // every punch with a wrong time. Verified on hardware — 330 restores sub-second accuracy.
      // Never express this in fractional hours; see IclockOptionsTest for the format guard.
      return new Options(30, 30, "00:00;14:05", 1, "1111000000", "330", 1, 0, "2.4.1");
    }
  }
}
