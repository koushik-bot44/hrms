package com.ihrms.web;

import java.util.List;

/**
 * A 400 carrying a list of {@code "field: message"} strings — rendered as the {@code message}
 * array of the error envelope (§1.5), matching the archived per-section validation errors.
 */
public class ValidationException extends RuntimeException {

  private final transient List<String> messages;

  public ValidationException(List<String> messages) {
    super(String.join("; ", messages));
    this.messages = List.copyOf(messages);
  }

  public List<String> messages() {
    return messages;
  }
}
