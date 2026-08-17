package com.ihrms.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard for the production security posture (audit C1). Under the {@code prod} profile it REFUSES to
 * start if any signing/encryption secret is still blank or a committed dev default — closing the window where a
 * missing env var silently ships forgeable JWTs, public-key-"encrypted" PII, or a forgeable storage-blob token.
 * Under a NON-prod profile it never fails (local dev MUST still boot with the built-in defaults) but logs a loud
 * warning if it is pointed at a non-local database — the signal that a real deploy is missing
 * {@code SPRING_PROFILES_ACTIVE=prod}, in which case {@code DataSeeder} (the hardcoded {@code SuperAdmin@123}
 * super-admin, reachable at {@code POST /auth/login}) and the plaintext password returns are both active.
 */
@Component
public class StartupSecretsGuard {

  private static final Logger log = LoggerFactory.getLogger(StartupSecretsGuard.class);

  /** All committed dev-default secrets share this prefix (application.yml). Any value starting with it — or
   * blank — is rejected under prod, so the guard never drifts if a specific default string changes. */
  static final String DEV_PREFIX = "dev-insecure";

  private final AppProperties props;
  private final Environment env;

  public StartupSecretsGuard(AppProperties props, Environment env) {
    this.props = props;
    this.env = env;
  }

  @PostConstruct
  void enforce() {
    boolean prod = env.acceptsProfiles(Profiles.of("prod"));
    List<String> bad = insecureSecretsUnderProd(prod, props.jwtSecret(), props.refreshSecret(), props.fieldEncKey());
    if (!bad.isEmpty()) {
      throw new IllegalStateException(
          "Refusing to start under the 'prod' profile: "
              + String.join(", ", bad)
              + " is blank or still the insecure dev default. Set real values via environment variables "
              + "(JWT_SECRET, REFRESH_SECRET, FIELD_ENC_KEY) and redeploy.");
    }
    if (prod) {
      log.info(
          "Startup secrets guard: prod profile — JWT_SECRET, REFRESH_SECRET and FIELD_ENC_KEY are all set to "
              + "non-default values.");
      return;
    }
    // Non-prod: never fail (local dev), but warn loudly if this is clearly a real deployment (remote DB).
    String url = env.getProperty("spring.datasource.url", "");
    if (looksRemote(url)) {
      log.warn(
          "\n**************************************************************************************\n"
              + "* SECURITY WARNING: active profile is NOT 'prod' but the datasource looks REMOTE.    *\n"
              + "* In this mode DataSeeder seeds a SUPER_ADMIN with the hardcoded default password    *\n"
              + "* 'SuperAdmin@123' (usable at POST /auth/login), and provisioning + credential       *\n"
              + "* endpoints RETURN plaintext passwords in their responses. Set SPRING_PROFILES_ACTIVE *\n"
              + "* =prod (with real JWT_SECRET / REFRESH_SECRET / FIELD_ENC_KEY) on this deployment.   *\n"
              + "**************************************************************************************");
    }
  }

  /**
   * The guard's decision, pure and testable: under prod, the names of the secrets that are blank or a dev
   * default; empty otherwise (and always empty when {@code prod} is false — local dev boots with defaults).
   */
  static List<String> insecureSecretsUnderProd(
      boolean prod, String jwtSecret, String refreshSecret, String fieldEncKey) {
    if (!prod) {
      return List.of();
    }
    List<String> bad = new ArrayList<>();
    if (isInsecure(jwtSecret)) {
      bad.add("JWT_SECRET");
    }
    if (isInsecure(refreshSecret)) {
      bad.add("REFRESH_SECRET");
    }
    if (isInsecure(fieldEncKey)) {
      bad.add("FIELD_ENC_KEY");
    }
    return bad;
  }

  static boolean isInsecure(String secret) {
    return secret == null || secret.isBlank() || secret.strip().startsWith(DEV_PREFIX);
  }

  private static boolean looksRemote(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      return false;
    }
    String u = jdbcUrl.toLowerCase(Locale.ROOT);
    return !(u.contains("localhost") || u.contains("127.0.0.1") || u.contains("[::1]"));
  }
}
