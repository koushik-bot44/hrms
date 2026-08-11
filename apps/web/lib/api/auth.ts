import {
  AuthResultSchema,
  InviteContextSchema,
  OtpRequestResultSchema,
  SessionSchema,
  type AuthResult,
  type ChangePasswordInput,
  type InviteContext,
  type LoginAudience,
  type OtpRequestInput,
  type OtpRequestResult,
  type OtpVerifyInput,
  type Session,
  type StaffLoginInput,
} from '@/lib/contract';
import { apiFetch } from './client';

/** Auth API calls (§6). `skipAuth` keeps the public ones off the access-token/refresh path. */

/**
 * Credential sign-in: email + password (+ optional audience). `audience` names the door (§6) — the API refuses
 * a mismatched principal AFTER auth (403 `code:PORTAL_MISMATCH`); the general top-level door omits it.
 */
export function loginStaff(body: StaffLoginInput & { audience?: LoginAudience }): Promise<AuthResult> {
  return apiFetch('/auth/login', {
    method: 'POST',
    body,
    schema: AuthResultSchema,
    skipAuth: true,
  });
}

/** Staff self-service password change (authenticated). */
export function changePassword(body: ChangePasswordInput): Promise<unknown> {
  return apiFetch('/auth/change-password', { method: 'POST', body });
}

/** The invite `token` (from the emailed link) is the real gate — merged into both OTP calls (§6). */
export function requestOtp(body: OtpRequestInput & { token: string }): Promise<OtpRequestResult> {
  return apiFetch('/auth/request-otp', {
    method: 'POST',
    body,
    schema: OtpRequestResultSchema,
    skipAuth: true,
  });
}

export function verifyOtp(body: OtpVerifyInput & { token: string }): Promise<AuthResult> {
  return apiFetch('/auth/verify-otp', {
    method: 'POST',
    body,
    schema: AuthResultSchema,
    skipAuth: true,
  });
}

/**
 * Validate an invite token before showing the onboarding OTP form (§3.2/§6). Returns the door's context
 * (prefill email + company display info) for an active invite, or throws (410) for an invalid/expired one.
 */
export function validateInvite(token: string): Promise<InviteContext> {
  return apiFetch('/public/onboarding/invite/validate', {
    method: 'POST',
    body: { token },
    schema: InviteContextSchema,
    skipAuth: true,
  });
}

export function refreshSession(): Promise<AuthResult> {
  return apiFetch('/auth/refresh', { method: 'POST', schema: AuthResultSchema, skipAuth: true });
}

export function logout(): Promise<unknown> {
  return apiFetch('/auth/logout', { method: 'POST', skipAuth: true });
}

export function fetchMe(): Promise<Session> {
  return apiFetch('/auth/me', { schema: SessionSchema });
}
