package com.ihrms.auth;

import com.ihrms.config.AppProperties;
import com.ihrms.support.Durations;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds the httpOnly refresh cookie on the API domain (contract §1.4). Production is
 * Secure + SameSite=None for the cross-site web origin; dev relaxes to insecure + Lax so
 * localhost-over-http works. Scoped to {@code /auth} so it is only sent to refresh/logout.
 */
@Component
public class RefreshCookies {

  private final String name;
  private final boolean prod;
  private final Duration ttl;

  public RefreshCookies(AppProperties props, Environment env) {
    this.name = props.refreshCookieName();
    this.prod = env.acceptsProfiles(Profiles.of("prod"));
    this.ttl = Durations.parse(props.refreshTokenTtl());
  }

  public ResponseCookie set(String token) {
    return build(token, ttl.getSeconds());
  }

  public ResponseCookie clear() {
    return build("", 0);
  }

  private ResponseCookie build(String value, long maxAgeSeconds) {
    return ResponseCookie.from(name, value)
        .httpOnly(true)
        .secure(prod)
        .sameSite(prod ? "None" : "Lax")
        .path("/auth")
        .maxAge(maxAgeSeconds)
        .build();
  }
}
