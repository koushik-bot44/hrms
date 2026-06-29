package com.ihrms.config;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Translates a libpq-style {@code DATABASE_URL}
 * ({@code postgresql://user:pass@host:port/db}) into Spring's
 * {@code spring.datasource.url/username/password} — so the same env var the archived
 * NestJS API (and Railway) used keeps working.
 *
 * <p>Precedence: explicit {@code SPRING_DATASOURCE_URL} wins; otherwise {@code DATABASE_URL}
 * is parsed; otherwise a localhost fallback is set so the app still boots (and
 * {@code /health} reports {@code db:"down"}) without any DB configured.
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication app) {
    if (hasText(env.getProperty("spring.datasource.url"))
        || hasText(env.getProperty("SPRING_DATASOURCE_URL"))) {
      return; // explicit Spring datasource config takes precedence
    }

    Map<String, Object> props = new HashMap<>();
    String databaseUrl = env.getProperty("DATABASE_URL");

    if (hasText(databaseUrl)) {
      try {
        URI uri = new URI(databaseUrl);
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String db = uri.getPath() == null ? "" : uri.getPath();
        props.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + db);
        String userInfo = uri.getUserInfo();
        if (userInfo != null) {
          int sep = userInfo.indexOf(':');
          props.put("spring.datasource.username", sep >= 0 ? userInfo.substring(0, sep) : userInfo);
          if (sep >= 0) {
            props.put("spring.datasource.password", userInfo.substring(sep + 1));
          }
        }
      } catch (Exception e) {
        throw new IllegalStateException("Invalid DATABASE_URL: " + e.getMessage(), e);
      }
    } else {
      // No DB configured — boot anyway; /health will report db:"down".
      props.put("spring.datasource.url", "jdbc:postgresql://localhost:5432/ihrms");
    }

    env.getPropertySources().addLast(new MapPropertySource("ihrms-database-url", props));
  }

  private static boolean hasText(String s) {
    return s != null && !s.isBlank();
  }
}
