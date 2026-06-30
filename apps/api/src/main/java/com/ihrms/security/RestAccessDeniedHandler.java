package com.ihrms.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ihrms.web.ApiErrors;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/** Emits the contract error envelope (403) when an authenticated principal lacks the role/scope. */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

  private final ObjectMapper mapper;

  public RestAccessDeniedHandler(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void handle(
      HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
      throws IOException {
    response.setStatus(HttpStatus.FORBIDDEN.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(
        response.getOutputStream(),
        ApiErrors.body(HttpStatus.FORBIDDEN.value(), "Forbidden", request.getRequestURI()));
  }
}
