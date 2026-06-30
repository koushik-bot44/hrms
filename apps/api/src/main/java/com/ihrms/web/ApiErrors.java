package com.ihrms.web;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Builds the canonical error envelope (docs/api-contract.md §1.5):
 * {@code { statusCode, error, message, path, timestamp }}. Shared by the controller-advice
 * and the Spring Security entry-point / access-denied handlers so every non-2xx response
 * has the identical shape the frontend expects.
 */
public final class ApiErrors {

  private ApiErrors() {}

  public static Map<String, Object> body(int status, Object message, String path) {
    HttpStatus resolved = HttpStatus.resolve(status);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("statusCode", status);
    body.put("error", resolved != null ? resolved.name() : "ERROR");
    body.put("message", message);
    body.put("path", path);
    body.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
    return body;
  }
}
