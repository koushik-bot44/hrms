package com.ihrms.auth;

import com.ihrms.config.AppProperties;
import com.ihrms.domain.enums.UserRole;
import com.ihrms.support.Durations;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Signs/verifies the access + refresh JWTs (HS256), reproducing the archived claim shape:
 * {@code { sub, typ:access|refresh, actor, role?, companyId?, teamId?, name?, employeeCode?, email }}.
 */
@Service
public class TokenService {

  /** Refresh-token claims (subject + actor kind). */
  public record RefreshClaims(String subject, String actor) {}

  /** Thrown on a structurally-invalid token (wrong {@code typ}); signature/expiry throw JwtException. */
  public static class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
      super(message);
    }
  }

  private final SecretKey accessKey;
  private final SecretKey refreshKey;
  private final Duration accessTtl;
  private final Duration refreshTtl;

  public TokenService(AppProperties props) {
    this.accessKey = Keys.hmacShaKeyFor(props.jwtSecret().getBytes(StandardCharsets.UTF_8));
    this.refreshKey = Keys.hmacShaKeyFor(props.refreshSecret().getBytes(StandardCharsets.UTF_8));
    this.accessTtl = Durations.parse(props.accessTokenTtl());
    this.refreshTtl = Durations.parse(props.refreshTokenTtl());
  }

  public String issueAccess(IhrmsPrincipal principal) {
    Instant now = Instant.now();
    var builder =
        Jwts.builder()
            .subject(principal.id())
            .claim("typ", "access")
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(accessTtl)));
    if (principal instanceof IhrmsPrincipal.User u) {
      builder
          .claim("actor", "USER")
          .claim("role", u.role().name())
          .claim("companyId", u.companyId()) // null removes the claim
          .claim("teamId", u.teamId())
          .claim("name", u.name())
          .claim("email", u.email());
    } else {
      IhrmsPrincipal.Employee e = (IhrmsPrincipal.Employee) principal;
      builder
          .claim("actor", "EMPLOYEE")
          .claim("employeeCode", e.employeeCode())
          .claim("companyId", e.companyId())
          .claim("email", e.email())
          .claim("name", e.name()) // null removes the claim (present once credentialed, §8)
          .claim("mailAddress", e.mailAddress());
    }
    return builder.signWith(accessKey, Jwts.SIG.HS256).compact();
  }

  public String issueRefresh(IhrmsPrincipal principal) {
    Instant now = Instant.now();
    return Jwts.builder()
        .subject(principal.id())
        .claim("typ", "refresh")
        .claim("actor", principal instanceof IhrmsPrincipal.User ? "USER" : "EMPLOYEE")
        .issuedAt(Date.from(now))
        .expiration(Date.from(now.plus(refreshTtl)))
        .signWith(refreshKey, Jwts.SIG.HS256)
        .compact();
  }

  /** Verify an access token and rebuild the principal. Throws on invalid/expired. */
  public IhrmsPrincipal verifyAccess(String token) {
    Claims claims = Jwts.parser().verifyWith(accessKey).build().parseSignedClaims(token).getPayload();
    if (!"access".equals(claims.get("typ", String.class))) {
      throw new InvalidTokenException("Not an access token");
    }
    String actor = claims.get("actor", String.class);
    if ("USER".equals(actor)) {
      String role = claims.get("role", String.class);
      if (role == null) {
        throw new InvalidTokenException("Missing role");
      }
      return new IhrmsPrincipal.User(
          claims.getSubject(),
          claims.get("email", String.class),
          claims.get("name", String.class) == null ? "" : claims.get("name", String.class),
          UserRole.valueOf(role),
          claims.get("companyId", String.class),
          claims.get("teamId", String.class));
    }
    if ("EMPLOYEE".equals(actor)) {
      return new IhrmsPrincipal.Employee(
          claims.getSubject(),
          claims.get("employeeCode", String.class),
          claims.get("email", String.class),
          claims.get("companyId", String.class),
          claims.get("name", String.class),
          claims.get("mailAddress", String.class));
    }
    throw new InvalidTokenException("Unknown actor");
  }

  /** Verify a refresh token. Throws on invalid/expired. */
  public RefreshClaims verifyRefresh(String token) {
    Claims claims = Jwts.parser().verifyWith(refreshKey).build().parseSignedClaims(token).getPayload();
    if (!"refresh".equals(claims.get("typ", String.class))) {
      throw new InvalidTokenException("Not a refresh token");
    }
    return new RefreshClaims(claims.getSubject(), claims.get("actor", String.class));
  }
}
