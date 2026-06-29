package com.ihrms.config;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS from {@code CORS_ORIGINS} (comma-separated). Credentials are allowed so the
 * httpOnly refresh cookie flows from the web origin — matching the archived API.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

  private final AppProperties props;

  public CorsConfig(AppProperties props) {
    this.props = props;
  }

  @Override
  public void addCorsMappings(CorsRegistry registry) {
    List<String> origins = props.corsOriginList();
    if (origins.isEmpty()) {
      return;
    }
    registry
        .addMapping("/**")
        .allowedOrigins(origins.toArray(String[]::new))
        .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        .allowedHeaders("*")
        .allowCredentials(true)
        .maxAge(3600);
  }
}
