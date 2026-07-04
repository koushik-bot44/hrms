package com.ihrms.security;

import com.ihrms.auth.AuthorizationService;
import com.ihrms.auth.IhrmsPrincipal;
import com.ihrms.auth.TokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates each request from the {@code Authorization: Bearer <accessToken>} header.
 * A valid token sets an {@link IhrmsAuthentication} (authority {@code ROLE_<role>} for staff,
 * {@code ROLE_EMPLOYEE} for employees). An invalid/expired/absent token leaves the request
 * anonymous, so the authorization rules return 401/403. Instantiated by {@link SecurityConfig}
 * (not a {@code @Component}) so Boot does not also auto-register it as a global servlet filter.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final TokenService tokens;
  private final AuthorizationService authz;

  public JwtAuthenticationFilter(TokenService tokens, AuthorizationService authz) {
    this.tokens = tokens;
    this.authz = authz;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null && header.startsWith("Bearer ")) {
      try {
        IhrmsPrincipal principal = tokens.verifyAccess(header.substring(7));
        // Archived-company denial (§6): a principal under a DELETED company is rejected on every
        // request, so already-issued tokens stop working. SUPER_ADMIN (null companyId) passes.
        if (authz.isPrincipalCompanyDeleted(principal)) {
          SecurityContextHolder.clearContext();
        } else {
          SecurityContextHolder.getContext()
              .setAuthentication(new IhrmsAuthentication(principal, authorities(principal)));
        }
      } catch (RuntimeException invalid) {
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(request, response);
  }

  private static List<GrantedAuthority> authorities(IhrmsPrincipal principal) {
    String role =
        principal instanceof IhrmsPrincipal.User u ? u.role().name() : "EMPLOYEE";
    return List.of(new SimpleGrantedAuthority("ROLE_" + role));
  }
}
