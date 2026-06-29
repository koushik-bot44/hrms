package com.ihrms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** springdoc OpenAPI metadata + a bearer-JWT security scheme (matches the API's auth). */
@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI ihrmsOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("IHRMS API")
                .version("0.1.0")
                .description("IHRMS backend. Contract: docs/api-contract.md"))
        .components(
            new Components()
                .addSecuritySchemes(
                    "bearer-jwt",
                    new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
        .addSecurityItem(new SecurityRequirement().addList("bearer-jwt"));
  }
}
