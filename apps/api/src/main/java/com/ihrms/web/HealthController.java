package com.ihrms.web;

import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /health} — the web's status indicator polls this and expects
 * {@code {status:"ok", db:"up"|"down"}} (see docs/api-contract.md §3.1). Distinct from
 * actuator's {@code /actuator/health} (ops-only, different shape).
 */
@RestController
public class HealthController {

  private final JdbcTemplate jdbc;

  public HealthController(ObjectProvider<JdbcTemplate> jdbcProvider) {
    this.jdbc = jdbcProvider.getIfAvailable();
  }

  @GetMapping("/health")
  public Map<String, String> health() {
    String db = "down";
    if (jdbc != null) {
      try {
        jdbc.queryForObject("SELECT 1", Integer.class);
        db = "up";
      } catch (Exception ignored) {
        db = "down";
      }
    }
    return Map.of("status", "ok", "db", db);
  }
}
