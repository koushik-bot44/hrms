package com.ihrms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pure unit test (no Spring context, no DB) — so it runs on EVERY build, including a plain {@code mvn test}.
 * Proves the prod boot guard (audit C1) fails a simulated prod boot with default secrets and passes with real
 * ones, and never blocks local dev.
 */
class StartupSecretsGuardTest {

  private static final String JWT_DEFAULT = "dev-insecure-access-secret-change-me-please-0001";
  private static final String REFRESH_DEFAULT = "dev-insecure-refresh-secret-change-me-please-0001";
  private static final String FIELD_DEFAULT = "dev-insecure-field-enc-key-change-me-please-0001";
  private static final String REAL = "a-genuinely-random-production-secret-value-0f9a8b7c6d";

  @Test
  void underProdEveryBlankOrDevDefaultSecretIsFlagged() {
    assertThat(StartupSecretsGuard.insecureSecretsUnderProd(true, JWT_DEFAULT, REFRESH_DEFAULT, FIELD_DEFAULT))
        .containsExactly("JWT_SECRET", "REFRESH_SECRET", "FIELD_ENC_KEY");
    // per-field: only the offending one is named
    assertThat(StartupSecretsGuard.insecureSecretsUnderProd(true, REAL, JWT_DEFAULT, REAL))
        .containsExactly("REFRESH_SECRET");
    assertThat(StartupSecretsGuard.insecureSecretsUnderProd(true, REAL, REAL, "  "))
        .containsExactly("FIELD_ENC_KEY");
  }

  @Test
  void underProdRealSecretsPass() {
    assertThat(StartupSecretsGuard.insecureSecretsUnderProd(true, REAL, REAL, REAL)).isEmpty();
  }

  @Test
  void nonProdNeverFlags() {
    // Local dev must boot with the committed defaults.
    assertThat(StartupSecretsGuard.insecureSecretsUnderProd(false, JWT_DEFAULT, REFRESH_DEFAULT, FIELD_DEFAULT))
        .isEmpty();
  }

  @Test
  void isInsecureCoversNullBlankAndDevPrefix() {
    assertThat(StartupSecretsGuard.isInsecure(null)).isTrue();
    assertThat(StartupSecretsGuard.isInsecure("")).isTrue();
    assertThat(StartupSecretsGuard.isInsecure("   ")).isTrue();
    assertThat(StartupSecretsGuard.isInsecure("dev-insecure-anything")).isTrue();
    assertThat(StartupSecretsGuard.isInsecure(REAL)).isFalse();
  }

  @Test
  void enforceFailsProdBootWithDefaultSecrets() {
    StartupSecretsGuard guard =
        new StartupSecretsGuard(props(JWT_DEFAULT, REFRESH_DEFAULT, FIELD_DEFAULT), prodEnv());
    assertThatThrownBy(guard::enforce)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("JWT_SECRET")
        .hasMessageContaining("REFRESH_SECRET")
        .hasMessageContaining("FIELD_ENC_KEY");
  }

  @Test
  void enforcePassesProdBootWithRealSecrets() {
    assertThatCode(() -> new StartupSecretsGuard(props(REAL, REAL, REAL), prodEnv()).enforce())
        .doesNotThrowAnyException();
  }

  @Test
  void enforceNeverFailsLocalDevEvenWithDefaults() {
    assertThatCode(
            () -> new StartupSecretsGuard(props(JWT_DEFAULT, REFRESH_DEFAULT, FIELD_DEFAULT), localEnv()).enforce())
        .doesNotThrowAnyException();
  }

  private static AppProperties props(String jwt, String refresh, String field) {
    return new AppProperties(null, null, jwt, refresh, null, null, null, 300, field, null, null, null, null);
  }

  private static MockEnvironment prodEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("prod");
    return env;
  }

  private static MockEnvironment localEnv() {
    MockEnvironment env = new MockEnvironment();
    env.setActiveProfiles("local");
    return env;
  }
}
