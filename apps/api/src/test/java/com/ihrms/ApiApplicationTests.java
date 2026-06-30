package com.ihrms;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the application context loads (runs Flyway + Hibernate schema validation).
 * Requires a local Postgres, so it is gated on {@code IHRMS_TEST_DB} — {@code ./mvnw
 * package} stays green without a DB (the Docker build uses -DskipTests).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "IHRMS_TEST_DB", matches = ".+")
class ApiApplicationTests {

  @Test
  void contextLoads() {}
}
