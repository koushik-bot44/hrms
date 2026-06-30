package com.ihrms.auth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Auth request/response DTOs — JSON shapes match docs/api-contract.md §3.2 exactly. */
public final class AuthDtos {

  private AuthDtos() {}

  public record StaffLoginRequest(
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email,
      @NotBlank(message = "Password is required") @Size(min = 8, message = "At least 8 characters")
          String password) {}

  /** Employee start-login: full name + email -> OTP to the email (the security factor). */
  public record EmployeeOtpRequest(
      @NotBlank(message = "Full name is required") String fullName,
      @NotBlank(message = "Email is required") @Email(message = "Enter a valid email") String email) {}

  /** Employee verify: email + the 6-digit OTP -> session. */
  public record EmployeeOtpVerifyRequest(
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
