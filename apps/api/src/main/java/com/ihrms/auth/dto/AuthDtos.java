package com.ihrms.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Auth request/response DTOs (§6). Two audiences: <b>staff</b> sign in with email + password
 * ({@link StaffLoginRequest}) at {@code /login}; <b>employees</b> sign in with full name + email →
 * OTP ({@link OtpRequest}/{@link OtpVerifyRequest}) at {@code /employee/login}. Staff passwords are
 * self-service changeable ({@link ChangePasswordRequest}).
 */
public final class AuthDtos {

  /** Minimum password length wherever a staff password is set (provisioning + change-password). */
  public static final int MIN_PASSWORD_LENGTH = 8;

  private AuthDtos() {}

  /** Staff sign-in: email + password → session (resolves a User only). */
  public record StaffLoginRequest(
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank(message = "Password is required") String password) {}

  /** Staff self-service password change (authenticated). */
  public record ChangePasswordRequest(
      @NotBlank(message = "Your current password is required") String currentPassword,
      @NotBlank(message = "A new password is required")
          @Size(min = MIN_PASSWORD_LENGTH, message = "Use at least 8 characters")
          String newPassword) {}

  /**
   * Employee sign-in start: full name + email + the invite {@code token} → OTP to the email. The token
   * (from the emailed link) is the real gate — an unauthenticated caller cannot request an OTP for an
   * arbitrary email without it (§6).
   */
  public record OtpRequest(
      @NotBlank(message = "Full name is required") String fullName,
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank(message = "This sign-in must be opened from your invite link") String token) {}

  /** Employee verify: email + the 6-digit OTP + the invite {@code token} → session. */
  public record OtpVerifyRequest(
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code") String otp,
      @NotBlank(message = "This sign-in must be opened from your invite link") String token) {}

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
