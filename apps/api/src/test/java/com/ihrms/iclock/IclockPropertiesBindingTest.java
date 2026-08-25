package com.ihrms.iclock;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@code app.iclock} from the REAL {@code application.yml} without starting the app.
 *
 * <p>Worth its own test because a binding error in that block is only discovered at startup, and the
 * full {@code @SpringBootTest} that would catch it needs a Postgres and is skipped without one. This
 * runs everywhere, including CI with no database.
 */
class IclockPropertiesBindingTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
          .withUserConfiguration(BindingConfig.class);

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(IclockProperties.class)
  static class BindingConfig {}

  @Test
  void bindsTheRealApplicationYmlIncludingTheNestedOptionsBlock() {
    runner.run(
        (AssertableApplicationContext context) -> {
          assertThat(context).hasNotFailed();
          IclockProperties props = context.getBean(IclockProperties.class);

          // Default posture: inert in production until an operator turns it on.
          assertThat(props.enabled()).isFalse();
          assertThat(props.maxBodyBytes()).isEqualTo(262_144);
          assertThat(props.rateLimitPerMinute()).isEqualTo(600);
          assertThat(props.ackWithCount()).isTrue();

          // Default capture verbosity stays 'full' until live push is verified.
          assertThat(props.leanCapture()).isFalse();

          assertThat(props.options()).isNotNull();
          assertThat(props.options().realtime()).isEqualTo(1);
          assertThat(props.options().delay()).isEqualTo(30);
          assertThat(props.options().serverVer()).isEqualTo("2.4.1");
          // The bound value from the real yml must be integer minutes, not fractional hours —
          // fractional is what put a live terminal 30 minutes slow.
          assertThat(props.options().timeZone()).matches("-?\\d+").isEqualTo("330");
          // The quoted default contains colons — this is the assertion that catches a YAML slip.
          assertThat(props.options().transTimes()).isEqualTo("00:00;14:05");
        });
  }

  @Test
  void appliesSafeFallbacksWhenEnvVarsAreBlankedOut() {
    // A zeroed max-body-bytes would otherwise mean "read nothing"; a zeroed rate limit would reject
    // every request. Neither should be reachable from a mis-set env var.
    IclockProperties props = new IclockProperties(true, 0, 0, "  ", "  ", null);

    assertThat(props.maxBodyBytes()).isEqualTo(262_144);
    assertThat(props.rateLimitPerMinute()).isEqualTo(600);
    assertThat(props.ackWithCount()).isTrue();
    // A blanked capture-mode must not silently become lean and start dropping bodies.
    assertThat(props.leanCapture()).isFalse();
    assertThat(props.options()).isNotNull();
    assertThat(props.options().realtime()).isEqualTo(1);
  }

  @Test
  void leanCaptureIsOptInOnly() {
    assertThat(new IclockProperties(true, 1024, 60, "count", "lean", null).leanCapture()).isTrue();
    assertThat(new IclockProperties(true, 1024, 60, "count", "FULL", null).leanCapture()).isFalse();
    assertThat(new IclockProperties(true, 1024, 60, "count", "nonsense", null).leanCapture()).isFalse();
  }

  @Test
  void honoursThePlainAckFormatWhenFirmwareNeedsABareOk() {
    IclockProperties props =
        new IclockProperties(true, 1024, 60, "plain", "full", IclockProperties.Options.defaults());

    assertThat(props.ackWithCount()).isFalse();
  }
}
