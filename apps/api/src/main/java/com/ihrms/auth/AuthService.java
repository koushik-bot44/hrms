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
import com.ihrms.auth.dto.SessionView;
import com.ihrms.config.AppProperties;
import com.ihrms.domain.model.Company;
import com.ihrms.domain.model.Employee;
import com.ihrms.domain.model.User;
import com.ihrms.domain.repository.CompanyRepository;
import com.ihrms.domain.repository.EmployeeRepository;
import com.ihrms.domain.repository.UserRepository;
import java.time.Instant;
import java.util.Map;
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
  private final CompanyRepository companies;
  private final PasswordEncoder encoder;
  private final TokenService tokens;
  private final MailService mail;
  private final AppProperties props;
  private final AuthorizationService authz;
  private final AuditService audit;

  public AuthService(
      UserRepository users,
      EmployeeRepository employees,
      CompanyRepository companies,
      PasswordEncoder encoder,
      TokenService tokens,
      MailService mail,
      AppProperties props,
      AuthorizationService authz,
      AuditService audit) {
    this.users = users;
    this.employees = employees;
    this.companies = companies;
    this.encoder = encoder;
    this.tokens = tokens;
    this.mail = mail;
    this.props = props;
    this.authz = authz;
    this.audit = audit;
  }

  // --- Staff: email + password (User only) ----------------------------------

  /**
   * Resolve by email + verify the password. A staff {@link User} by email OR — once credentialed (§8,
   * Stage 5) — an {@link Employee} by their MAILBOX address; either yields the matching session. Any
   * mismatch (unknown, no password, wrong password, archived company) is a single generic denial
   * (enumeration-safe). An employee without assigned credentials cannot sign in here.
   */
  public IssuedSession loginStaff(StaffLoginRequest req) {
    String email = req.email().trim().toLowerCase();

    User user = users.findByEmail(email).orElse(null);
    if (user != null) {
      if (user.getPasswordHash() == null
          || !"ACTIVE".equals(user.getStatus())
          || authz.isCompanyDeleted(user.getCompanyId())
          || !encoder.matches(req.password(), user.getPasswordHash())) {
        throw unauthorized("Invalid email or password");
      }
      return issue(Principals.of(user), PASSWORD);
    }

    Employee employee = employees.findByMailAddress(email).orElse(null);
    if (employee == null
        || employee.getPasswordHash() == null
        || authz.isCompanyDeleted(employee.getCompanyId())
        || !encoder.matches(req.password(), employee.getPasswordHash())) {
      throw unauthorized("Invalid email or password");
    }
    // §3.6: a deactivated account cannot sign in via the credentialed workspace door either. The password
    // already proved identity, so a clear message is fine (not an enumeration leak).
    if (employee.isAccountDeactivated()) {
      throw deactivated();
    }
    return issue(Principals.of(employee), PASSWORD);
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
        && !authz.isCompanyDeleted(employee.getCompanyId())
        && !employee.isAccountDeactivated()) { // §3.6: withhold the OTP from a deactivated account (enum-safe)
      String otp = issueOtp(email, employee.getCompanyId());
      employee.setOtpHash(encoder.encode(otp));
      employee.setOtpExpiresAt(Instant.now().plusSeconds(ttl));
      employees.save(employee);
      // The OTP is returned to the caller ONLY in dev-log mode; once real SMTP is active it travels by email
      // alone. Bound to the mail mode (not a separate prod flag) so the two can never drift (§Outbound email).
      devOtp = mail.isRealDelivery() ? null : otp;
    }
    return new OtpRequestResult(true, ttl, devOtp);
  }

  /** Generate + email an OTP; returns the raw code (for the dev-only echo). */
  private String issueOtp(String email, String companyId) {
    String otp = Principals.generateOtp();
    mail.sendOtp(email, otp, companyId);
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
    // §3.6: a DEACTIVATED account gets no new session (a clear message — the OTP flow proved identity).
    // Note: this is the deactivation flag, NOT status==OFFBOARDED — completion alone no longer blocks login.
    if (employee.isAccountDeactivated()) {
      throw deactivated();
    }
    assertOtpValid(employee.getOtpHash(), employee.getOtpExpiresAt(), req.otp());
    employee.setOtpHash(null); // single-use
    employee.setOtpExpiresAt(null);
    employees.save(employee);
    return issue(Principals.of(employee), OTP);
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
    // Preserve the door the session was opened with, so a refresh keeps the same landing area (Stage 6).
    return issue(reloadPrincipal(claims), claims.authMethod());
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
    // §3.6: a DEACTIVATED employee's session dies at the next refresh (covers BOTH doors — the refresh
    // preserves the original authMethod). Completion alone (status==OFFBOARDED) no longer ends the session.
    if (employee.isAccountDeactivated()) {
      throw deactivated();
    }
    return Principals.of(employee);
  }

  // --- Issuance -------------------------------------------------------------

  /** Issue tokens + the public session, tagging which door was used so the landing is stable (Stage 6). */
  private IssuedSession issue(IhrmsPrincipal principal, String authMethod) {
    String access = tokens.issueAccess(principal);
    String refresh = tokens.issueRefresh(principal, authMethod);
    return new IssuedSession(
        new AuthResult(access, Principals.toSession(principal, authMethod, companySlug(principal))),
        refresh);
  }

  /** Build the public session for a principal (used by {@code /auth/me}), resolving its company slug. */
  public SessionView sessionFor(IhrmsPrincipal principal) {
    return Principals.toSession(principal, null, companySlug(principal));
  }

  /** The signed-in company's URL slug (Stage 2 routing) — null for platform principals (no company). */
  private String companySlug(IhrmsPrincipal principal) {
    String companyId =
        principal instanceof IhrmsPrincipal.User u
            ? u.companyId()
            : ((IhrmsPrincipal.Employee) principal).companyId();
    return companyId == null
        ? null
        : companies.findById(companyId).map(Company::getSlug).orElse(null);
  }

  /** Login methods (carried in the session + refresh token). */
  private static final String PASSWORD = "PASSWORD";
  private static final String OTP = "OTP";

  private ResponseStatusException unauthorized(String message) {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, message);
  }

  /** The clear, shared denial for a deactivated account (§3.6) — both doors + refresh. */
  private ResponseStatusException deactivated() {
    return new ResponseStatusException(HttpStatus.FORBIDDEN, "This account has been deactivated");
  }
}
