package com.ihrms.web;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Reproduces the archived NestJS error envelope exactly so the frontend keeps working:
 *
 * <pre>{ statusCode, error, message, path, timestamp }</pre>
 *
 * where {@code error} is the SCREAMING_SNAKE reason ({@code HttpStatus.name()}),
 * {@code message} is a string (or a string[] for validation errors, each
 * {@code "field: message"}), {@code path} is the request URI, {@code timestamp} is ISO-8601.
 * See docs/api-contract.md §1.5.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(
      MethodArgumentNotValidException ex, HttpServletRequest req) {
    List<String> messages =
        ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .toList();
    return build(HttpStatus.BAD_REQUEST, messages.isEmpty() ? "Validation failed" : messages, req);
  }

  @ExceptionHandler(ValidationException.class)
  public ResponseEntity<Map<String, Object>> handleCustomValidation(
      ValidationException ex, HttpServletRequest req) {
    return build(HttpStatus.BAD_REQUEST, ex.messages(), req);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<Map<String, Object>> handleMissingParam(
      MissingServletRequestParameterException ex, HttpServletRequest req) {
    return build(HttpStatus.BAD_REQUEST, List.of(ex.getParameterName() + ": is required"), req);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<Map<String, Object>> handleUnreadable(
      HttpMessageNotReadableException ex, HttpServletRequest req) {
    return build(HttpStatus.BAD_REQUEST, "Malformed request body", req);
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<Map<String, Object>> handleMethod(
      HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
    return build(HttpStatus.METHOD_NOT_ALLOWED, ex.getMessage(), req);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<Map<String, Object>> handleResponseStatus(
      ResponseStatusException ex, HttpServletRequest req) {
    String message = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
    return build(ex.getStatusCode(), message, req);
  }

  @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
  public ResponseEntity<Map<String, Object>> handleNotFound(Exception ex, HttpServletRequest req) {
    return build(HttpStatus.NOT_FOUND, "Not found", req);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleAll(Exception ex, HttpServletRequest req) {
    log.error("Unhandled exception on {} {} -> 500", req.getMethod(), req.getRequestURI(), ex);
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error", req);
  }

  private ResponseEntity<Map<String, Object>> build(
      HttpStatusCode status, Object message, HttpServletRequest req) {
    HttpStatus resolved = HttpStatus.resolve(status.value());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("statusCode", status.value());
    body.put("error", resolved != null ? resolved.name() : "ERROR");
    body.put("message", message);
    body.put("path", req.getRequestURI());
    body.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
    return ResponseEntity.status(status).body(body);
  }
}
