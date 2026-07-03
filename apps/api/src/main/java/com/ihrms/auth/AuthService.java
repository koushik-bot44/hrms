package com.ihrms.auth;

import com.ihrms.auth.TokenService.RefreshClaims;
import com.ihrms.auth.dto.AuthDtos.AuthResult;
import com.ihrms.auth.dto.AuthDtos.OtpRequest;
import com.ihrms.auth.dto.AuthDtos.OtpRequestResult;
import com.ihrms.auth.dto.AuthDtos.OtpVerifyRequest;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unified authentication (§6). EVERYONE — staff (User) and employees — signs in with full name +
 * email → OTP; there are no passwords. The email resolves a single account across the User and
 * Employee tables (kept unambiguous by a creation-time uniqueness guard, {@link AccountEmails}); the
 * full name is matched against it; a single-use, time-boxed OTP is emailed. Requests are
 * enumeration-safe (a generic response either way) and rate-limited (RateLimitFilter on /auth/**).
 * Verify issues the session with the resolved account's principal type + role + scope. Refresh
 * reloads the principal from the DB so role/company/deactivation changes take effect.
 */
@Service
public class AuthService {

  /** Tokens + the public session returned to the controller (which sets the refresh cookie). */
  public record IssuedSession(AuthResult result, String refreshToken) {}

  private final UserRepository users;
  private final EmployeeRepository employees;
  private final PasswordEncoder encoder;
  private final TokenService tokens;
  private final MailService mail;
  private final AppProperties props;
  private final Environment env;

  public AuthService(
      UserRepository users,
      EmployeeRepository employees,
      PasswordEncoder encoder,
      TokenService tokens,
      MailService mail,
      AppProperties props,
      Environment env) {
    this.users = users;
    this.employees = employees;
    this.encoder = encoder;
    this.tokens = tokens;
    this.mail = mail;
    this.props = props;
    this.env = env;
  }

  // --- Start: full name + email -> OTP (staff OR employee) ------------------

  public OtpRequestResult requestOtp(OtpRequest req) {
    int ttl = props.otpTtl();
    String email = req.email().trim().toLowerCase();
    String fullName = req.fullName().trim();
    String devOtp = null;

    // Resolve the single account for this email (User first, then Employee — the uniqueness guard
    // guarantees at most one). Issue only when the full name matches; otherwise fall through to the
    // same generic response so neither email nor name can be enumerated.
    User user = users.findByEmail(email).orElse(null);
    if (user != null) {
      if ("ACTIVE".equals(user.getStatus()) && nameMatches(user.getName(), fullName)) {
        String otp = issueOtp(email);
        user.setOtpHash(encoder.encode(otp));
        user.setOtpExpiresAt(Instant.now().plusSeconds(ttl));
        users.save(user);
        devOtp = isProd() ? null : otp;
      }
    } else {
      Employee employee = employees.findByEmail(email).orElse(null);
      if (employee != null && nameMatches(employee.getFullName(), fullName)) {
        String otp = issueOtp(email);
        employee.setOtpHash(encoder.encode(otp));
        employee.setOtpExpiresAt(Instant.now().plusSeconds(ttl));
        employees.save(employee);
        devOtp = isProd() ? null : otp;
      }
    }
    return new OtpRequestResult(true, ttl, devOtp);
  }

  /** Generate + email an OTP; returns the raw code (for the dev-only echo). */
  private String issueOtp(String email) {
    String otp = Principals.generateOtp();
    mail.sendOtp(email, otp);
    return otp;
  }

  // --- Verify: email + code -> session --------------------------------------

  public IssuedSession verifyOtp(OtpVerifyRequest req) {
    String email = req.email().trim().toLowerCase();

    User user = users.findByEmail(email).orElse(null);
    if (user != null) {
      if (!"ACTIVE".equals(user.getStatus())) {
        throw unauthorized("Invalid or expired code");
      }
      assertOtpValid(user.getOtpHash(), user.getOtpExpiresAt(), req.otp());
      user.setOtpHash(null); // single-use
      user.setOtpExpiresAt(null);
      users.save(user);
      return issue(Principals.of(user));
    }

    Employee employee = employees.findByEmail(email).orElse(null);
    if (employee == null) {
      throw unauthorized("Invalid or expired code");
    }
    assertOtpValid(employee.getOtpHash(), employee.getOtpExpiresAt(), req.otp());
    employee.setOtpHash(null); // single-use
    employee.setOtpExpiresAt(null);
    employees.save(employee);
    return issue(Principals.of(employee));
  }

  private void assertOtpValid(String otpHash, Instant expiresAt, String provided) {
    if (otpHash == null
        || expiresAt == null
        || expiresAt.isBefore(Instant.now())
        || !encoder.matches(provided, otpHash)) {
      throw unauthorized("Invalid or expired code");
    }
  }

  private static boolean nameMatches(String stored, String provided) {
    return stored != null && stored.trim().equalsIgnoreCase(provided.trim());
  }

  // --- Refresh --------------------------------------------------------------

  public IssuedSession refresh(String refreshToken) {
    if (refreshToken == null || refreshToken.isBlank()) {
      throw unauthorized("No session");
    }
    RefreshClaims claims;
    try {
      claims = tokens.verifyRefresh(refreshToken);
    } catch (RuntimeException e) {
      throw unauthorized("Session expired");
    }
    return issue(reloadPrincipal(claims));
  }

  private IhrmsPrincipal reloadPrincipal(RefreshClaims claims) {
    if ("USER".equals(claims.actor())) {
      User user = users.findById(claims.subject()).orElse(null);
      if (user == null || !"ACTIVE".equals(user.getStatus())) {
        throw unauthorized("Session no longer valid");
      }
      return Principals.of(user);
    }
    Employee employee = employees.findById(claims.subject()).orElse(null);
    if (employee == null) {
      throw unauthorized("Session no longer valid");
    }
    return Principals.of(employee);
  }

  // --- Issuance -------------------------------------------------------------

  private IssuedSession issue(IhrmsPrincipal principal) {
    String access = tokens.issueAccess(principal);
    String refresh = tokens.issueRefresh(principal);
    return new IssuedSession(new AuthResult(access, Principals.toSession(principal)), refresh);
  }

  private boolean isProd() {
    return env.acceptsProfiles(Profiles.of("prod"));
  }

  private ResponseStatusException unauthorized(String message) {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
  }
}
