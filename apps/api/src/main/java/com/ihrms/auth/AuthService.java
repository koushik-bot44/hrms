package com.ihrms.auth;

import com.ihrms.auth.TokenService.RefreshClaims;
import com.ihrms.auth.dto.AuthDtos.AuthResult;
import com.ihrms.auth.dto.AuthDtos.EmployeeOtpRequest;
import com.ihrms.auth.dto.AuthDtos.EmployeeOtpVerifyRequest;
import com.ihrms.auth.dto.AuthDtos.OtpRequestResult;
import com.ihrms.auth.dto.AuthDtos.StaffLoginRequest;
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
 * Authentication (contract §3.2). Reproduces the archived behavior exactly: staff login
 * (email + password), employee OTP (enumeration-safe request -> single-use, time-boxed
 * verify), and refresh (reloads the principal from the DB so role/company/deactivation
 * changes take effect). Tokens are issued by {@link TokenService}.
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

  // --- Staff (email + password) ---------------------------------------------

  public IssuedSession loginStaff(StaffLoginRequest req) {
    User user = users.findByEmail(req.email().toLowerCase()).orElse(null);
    if (user == null || user.getPasswordHash() == null || !"ACTIVE".equals(user.getStatus())) {
      throw unauthorized("Invalid email or password");
    }
    if (!encoder.matches(req.password(), user.getPasswordHash())) {
      throw unauthorized("Invalid email or password");
    }
    return issue(Principals.of(user));
  }

  // --- Employee (employeeCode + email -> OTP) -------------------------------

  public OtpRequestResult requestEmployeeOtp(EmployeeOtpRequest req) {
    int ttl = props.otpTtl();
    String code = req.employeeCode().trim().toUpperCase();
    Employee employee = employees.findByEmployeeCode(code).orElse(null);

    // Issue only when code + email match; otherwise return the same shape (enumeration-safe).
    boolean matches =
        employee != null && employee.getEmail().equalsIgnoreCase(req.email().trim());
    if (matches) {
      String otp = Principals.generateOtp();
      employee.setOtpHash(encoder.encode(otp));
      employee.setOtpExpiresAt(Instant.now().plusSeconds(ttl));
      employees.save(employee);
      mail.sendOtp(employee.getEmail(), otp);
      return new OtpRequestResult(true, ttl, isProd() ? null : otp);
    }
    return new OtpRequestResult(true, ttl, null);
  }

  public IssuedSession verifyEmployeeOtp(EmployeeOtpVerifyRequest req) {
    String code = req.employeeCode().trim().toUpperCase();
    Employee employee = employees.findByEmployeeCode(code).orElse(null);
    if (employee == null || employee.getOtpHash() == null || employee.getOtpExpiresAt() == null) {
      throw unauthorized("Invalid or expired code");
    }
    if (employee.getOtpExpiresAt().isBefore(Instant.now())) {
      throw unauthorized("Invalid or expired code");
    }
    if (!encoder.matches(req.otp(), employee.getOtpHash())) {
      throw unauthorized("Invalid or expired code");
    }
    // Single-use: clear the OTP immediately so it can't be replayed.
    employee.setOtpHash(null);
    employee.setOtpExpiresAt(null);
    employees.save(employee);
    return issue(Principals.of(employee));
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
