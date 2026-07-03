package com.ihrms.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Auth request/response DTOs. Unified sign-in (§6): everyone — staff and employees — starts with
 * full name + email → OTP, then verifies email + code. No passwords.
 */
public final class AuthDtos {

  private AuthDtos() {}

  /** Start login: full name + email → OTP to the email (the security factor). */
  public record OtpRequest(
      @NotBlank(message = "Full name is required") String fullName,
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email) {}

  /** Verify: email + the 6-digit OTP → session. */
  public record OtpVerifyRequest(
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code") String otp) {}

  /** {@code AuthResult = { accessToken, session }}. */
  public record AuthResult(String accessToken, SessionView session) {}

  /** {@code { sent, expiresInSeconds, devOtp? }} — devOtp omitted (not null) when absent. */
  public record OtpRequestResult(
      boolean sent,
      int expiresInSeconds,
      @JsonInclude(JsonInclude.Include.NON_NULL) String devOtp) {}

  /** {@code { ok: true }} returned by /auth/logout. */
  public record OkResponse(boolean ok) {}
}
