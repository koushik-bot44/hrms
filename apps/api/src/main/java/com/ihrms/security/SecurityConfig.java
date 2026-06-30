package com.ihrms.security;

import com.ihrms.auth.TokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. Public + role-area authorization mirrors the contract §1.2;
 * fine-grained tenancy is enforced by {@code AuthorizationService} in the handlers
 * (method security is enabled for @PreAuthorize). 401/403 emit the contract error envelope.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http,
      TokenService tokens,
      RestAuthenticationEntryPoint entryPoint,
      RestAccessDeniedHandler accessDeniedHandler)
      throws Exception {
    http.csrf(AbstractHttpConfigurer::disable)
        // Uses the `corsConfigurationSource` bean (CorsConfig) by name.
        .cors(Customizer.withDefaults())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/health",
                        "/actuator/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/auth/login",
                        "/auth/employee/request-otp",
                        "/auth/employee/verify-otp",
                        "/auth/refresh",
                        "/auth/logout")
                    .permitAll()
                    .requestMatchers("/companies/**")
                    .hasRole("SUPER_ADMIN")
                    .requestMatchers("/teams/**")
                    .hasRole("COMPANY_ADMIN")
                    .requestMatchers("/employees/**")
                    .hasRole("HR")
                    .requestMatchers("/me/onboarding/**")
                    .hasRole("EMPLOYEE")
                    .requestMatchers("/manager/**")
                    .hasRole("MANAGER")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new JwtAuthenticationFilter(tokens), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
