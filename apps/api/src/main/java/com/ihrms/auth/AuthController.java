package com.ihrms.auth;

import com.ihrms.audit.AuditActor;
import com.ihrms.audit.AuditInterceptor;
import com.ihrms.auth.AuthService.IssuedSession;
import com.ihrms.auth.dto.AuthDtos.AuthResult;
import com.ihrms.auth.dto.AuthDtos.EmployeeOtpRequest;
import com.ihrms.auth.dto.AuthDtos.EmployeeOtpVerifyRequest;
import com.ihrms.auth.dto.AuthDtos.OkResponse;
import com.ihrms.auth.dto.AuthDtos.OtpRequestResult;
import com.ihrms.auth.dto.AuthDtos.StaffLoginRequest;
import com.ihrms.auth.dto.SessionView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Auth endpoints (contract §3.2). POST -> 201, GET -> 200. The refresh token is delivered
 * only in the httpOnly {@code ihrms_refresh} cookie; the access token is in the body. All
 * but {@code /auth/me} are public (see SecurityConfig); /auth/me requires authentication.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

  private final AuthService auth;
  private final RefreshCookies cookies;

  public AuthController(AuthService auth, RefreshCookies cookies) {
    this.auth = auth;
    this.cookies = cookies;
  }

  @PostMapping("/login")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResult login(
      @Valid @RequestBody StaffLoginRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    return complete(auth.loginStaff(body), request, response);
  }

  @PostMapping("/employee/request-otp")
  @ResponseStatus(HttpStatus.CREATED)
  public OtpRequestResult requestOtp(@Valid @RequestBody EmployeeOtpRequest body) {
    return auth.requestEmployeeOtp(body);
  }

  @PostMapping("/employee/verify-otp")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResult verifyOtp(
      @Valid @RequestBody EmployeeOtpVerifyRequest body,
      HttpServletRequest request,
      HttpServletResponse response) {
    return complete(auth.verifyEmployeeOtp(body), request, response);
  }

  @PostMapping("/refresh")
  @ResponseStatus(HttpStatus.CREATED)
  public AuthResult refresh(
      @CookieValue(value = "${app.refresh-cookie-name:ihrms_refresh}", required = false)
          String refreshToken,
      HttpServletRequest request,
      HttpServletResponse response) {
    return complete(auth.refresh(refreshToken), request, response);
  }

  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.CREATED)
  public OkResponse logout(HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear().toString());
    return new OkResponse(true);
  }

  @GetMapping("/me")
  public SessionView me(@AuthenticationPrincipal IhrmsPrincipal principal) {
    return Principals.toSession(principal);
  }

  /** Set the refresh cookie and the audit actor (no SecurityContext on @Public auth routes). */
  private AuthResult complete(
      IssuedSession issued, HttpServletRequest request, HttpServletResponse response) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookies.set(issued.refreshToken()).toString());
    request.setAttribute(
        AuditInterceptor.AUDIT_ACTOR_ATTR, AuditActor.from(issued.result().session()));
    return issued.result();
  }
}
