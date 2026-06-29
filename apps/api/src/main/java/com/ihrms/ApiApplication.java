package com.ihrms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * IHRMS backend (Spring Boot). Replaces the archived NestJS API (git tag
 * {@code archive/ihrms-node}). The HTTP contract it must satisfy is documented in
 * {@code docs/api-contract.md}. Domain modules (auth, companies, teams, employees,
 * onboarding, audit, storage) are added in later phases.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ApiApplication {
  public static void main(String[] args) {
    SpringApplication.run(ApiApplication.class, args);
  }
}
