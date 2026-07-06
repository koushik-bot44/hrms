package com.ihrms.auth;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditService;
import com.ihrms.auth.TokenService.RefreshClaims;
import com.ihrms.auth.dto.AuthDtos.AuthResult;
import com.ihrms.auth.dto.AuthDtos.ChangePasswordRequest;
import com.ihrms.auth.dto.AuthDtos.OtpRequest;
import com.ihrms.auth.dto.AuthDtos.OtpRequestResult;
import com.ihrms.auth.dto.AuthDtos.OtpVerifyRequest;
import com.ihrms.auth.dto.AuthDtos.StaffLoginRequest;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authentication (§6). Two audiences, each resolving against ONE table so they never cross over:
 * <b>staff</b> (User) sign in with email + password ({@link #loginStaff}); <b>employees</b> sign in
 * with full name + email → OTP ({@link #requestOtp}/{@link #verifyOtp}). Both apply the deleted-company
 * denial, are enumeration-safe (a generic response either way), and are rate-limited (RateLimitFilter
 * on /auth/**). Passwords are hashed with the app's {@link PasswordEncoder} (BCrypt) and are
 * self-service changeable ({@link #changePassword}). Refresh reloads the principal from the DB so
 * role/company/deactivation changes take effect.
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
  private final AuthorizationService authz;
  private final AuditService audit;

  public AuthService(
      UserRepository users,
      EmployeeRepository employees,
      PasswordEncoder encoder,
      TokenService tokens,
      MailService mail,
      AppProperties props,
      Environment env,
      AuthorizationService authz,
      AuditService audit) {
    this.users = users;
    this.employees = employees;
    this.encoder = encoder;
    this.tokens = tokens;
    this.mail = mail;
    this.props = props;
    this.env = env;
    this.authz = authz;
    this.audit = audit;
  }

  // --- Staff: email + password (User only) ----------------------------------

  /** Resolve a User by email and verify the password. Employee/unknown email → generic denial. */
  public IssuedSession loginStaff(StaffLoginRequest req) {
    User user = users.findByEmail(req.email().trim().toLowerCase()).orElse(null);
    if (user == null
        || user.getPasswordHash() == null
        || !"ACTIVE".equals(user.getStatus())
        || authz.isCompanyDeleted(user.getCompanyId())
        || !encoder.matches(req.password(), user.getPasswordHash())) {
      throw unauthorized("Invalid email or password");
    }
    return issue(Principals.of(user));
  }

  /** Staff self-service password change: verify current, rehash, audit PASSWORD_CHANGED. */
  public void changePassword(IhrmsPrincipal.User actor, ChangePasswordRequest req, String ip) {
    User user =
        users.findById(actor.userId()).orElseThrow(() -> unauthorized("Session no longer valid"));
    if (user.getPasswordHash() == null
        || !encoder.matches(req.currentPassword(), user.getPasswordHash())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Current password is incorrect");
    }
    user.setPasswordHash(encoder.encode(req.newPassword()));
    users.save(user);
    audit.record(AuditActor.from(actor), "PASSWORD_CHANGED", "User", user.getId(), Map.of(), ip);
  }

  // --- Employee: full name + email -> OTP (Employee only) -------------------

  public OtpRequestResult requestOtp(OtpRequest req) {
    int ttl = props.otpTtl();
    String email = req.email().trim().toLowerCase();
    String fullName = req.fullName().trim();
    String devOtp = null;

    // Resolve an Employee by email (staff use email+password, not OTP). Issue only when the
    // HR-entered full name matches and the company is not archived; otherwise fall through to the
    // same generic response so neither email nor name can be enumerated (§6).
    Employee employee = employees.findByEmail(email).orElse(null);
    if (employee != null
        && nameMatches(employee.getFullName(), fullName)
        && !authz.isCompanyDeleted(employee.getCompanyId())) {
      String otp = issueOtp(email);
      employee.setOtpHash(encoder.encode(otp));
      employee.setOtpExpiresAt(Instant.now().plusSeconds(ttl));
      employees.save(employee);
      devOtp = isProd() ? null : otp;
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

    // Employee only — a staff email resolves nothing here (generic denial).
    Employee employee = employees.findByEmail(email).orElse(null);
    if (employee == null || authz.isCompanyDeleted(employee.getCompanyId())) {
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
      if (user == null
          || !"ACTIVE".equals(user.getStatus())
          || authz.isCompanyDeleted(user.getCompanyId())) {
        throw unauthorized("Session no longer valid");
      }
      return Principals.of(user);
    }
    Employee employee = employees.findById(claims.subject()).orElse(null);
    if (employee == null || authz.isCompanyDeleted(employee.getCompanyId())) {
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
