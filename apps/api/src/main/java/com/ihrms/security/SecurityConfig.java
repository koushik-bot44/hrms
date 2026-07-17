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
      com.ihrms.auth.AuthorizationService authz,
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
                        "/auth/request-otp",
                        "/auth/verify-otp",
                        "/auth/refresh",
                        "/auth/logout")
                    .permitAll()
                    // db-storage blob endpoint: the encrypted path token is the authorization
                    // (a presigned-URL equivalent), so no JWT is required.
                    .requestMatchers("/storage/blobs/**")
                    .permitAll()
                    .requestMatchers("/companies/**")
                    .hasRole("SUPER_ADMIN")
                    .requestMatchers("/teams/**")
                    .hasRole("COMPANY_ADMIN")
                    // Mailbox credentials (§6): a COMPANY_ADMIN may assign/reset for ANY approved
                    // employee in their company, alongside the onboarding HR. That needs the employee
                    // LIST (company-wide search) + the READ record + the credentials write opened to
                    // COMPANY_ADMIN too; the service scopes each to the actor's own company. Everything
                    // else under /employees/** (onboard, verify, route, reveal, lookup) stays HR-only.
                    .requestMatchers(HttpMethod.GET, "/employees")
                    .hasAnyRole("HR", "COMPANY_ADMIN")
                    .requestMatchers(HttpMethod.GET, "/employees/*/record")
                    .hasAnyRole("HR", "COMPANY_ADMIN")
                    .requestMatchers(HttpMethod.POST, "/employees/*/credentials")
                    .hasAnyRole("HR", "COMPANY_ADMIN")
                    // Form-2 edit at onboard (§3.2): HR (own onboarded) + SUPER_ADMIN (cross-company);
                    // the service scopes access and enforces the INVITED-only lock.
                    .requestMatchers(HttpMethod.PATCH, "/employees/*/form2")
                    .hasAnyRole("HR", "SUPER_ADMIN")
                    .requestMatchers("/employees/**")
                    .hasRole("HR")
                    .requestMatchers("/me/onboarding/**")
                    .hasRole("EMPLOYEE")
                    .requestMatchers("/manager/**")
                    .hasRole("MANAGER")
                    .requestMatchers("/audit/**")
                    .hasAnyRole("SUPER_ADMIN", "COMPANY_ADMIN")
                    // The read-only viewer area serves BOTH the cross-company Accounts Admin and the
                    // team-scoped Accountant (the service scopes by role). Provisioning is SUPER_ADMIN-only.
                    .requestMatchers("/accountant/**")
                    .hasAnyRole("ACCOUNTS_ADMIN", "ACCOUNTANT")
                    .requestMatchers("/provisioning/**")
                    .hasRole("SUPER_ADMIN")
                    // Internal mail (§8): any authenticated account reaches its OWN mailbox; the send
                    // graph (canSendMail) is the real gate, applied per-message in the service. Staff
                    // always have a mailbox; a credentialed EMPLOYEE (§8, Stage 5) does too — the service
                    // refuses an employee WITHOUT assigned credentials (403).
                    .requestMatchers("/mail/**")
                    .authenticated()
                    // Attendance (§8a): the /team/** views are MANAGER-only (team-scoped in the service);
                    // the employee clock/status endpoints are open to any authenticated principal, and the
                    // service refuses a non-employee / an uncredentialed employee with 403.
                    .requestMatchers("/attendance/team/**")
                    .hasRole("MANAGER")
                    .requestMatchers("/attendance/**")
                    .authenticated()
                    // Leave (§8b): /team/** decisions + queue are MANAGER-only (approver-scoped in the
                    // service); the employee submit/history/cancel endpoints are open to any authenticated
                    // principal, and the service refuses a non-employee / uncredentialed employee with 403.
                    .requestMatchers("/leave/team/**")
                    .hasRole("MANAGER")
                    .requestMatchers("/leave/**")
                    .authenticated()
                    // Web Push (§ Web Push): any authenticated principal may register their own browser
                    // subscription + self-test; the service refuses an uncredentialed employee (403).
                    .requestMatchers("/push/**")
                    .authenticated()
                    .anyRequest()
                    .authenticated())
        // Hardened response headers (§6): nosniff, frame DENY, HSTS (prod/HTTPS), no-referrer.
        .headers(
            headers ->
                headers
                    .contentTypeOptions(Customizer.withDefaults())
                    .frameOptions(frame -> frame.deny())
                    .httpStrictTransportSecurity(
                        hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31_536_000))
                    .referrerPolicy(
                        referrer ->
                            referrer.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        .exceptionHandling(
            e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDeniedHandler))
        .addFilterBefore(
            new JwtAuthenticationFilter(tokens, authz), UsernamePasswordAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
}
