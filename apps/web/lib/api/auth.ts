import {
  AuthResultSchema,
  OtpRequestResultSchema,
  SessionSchema,
  type AuthResult,
  type ChangePasswordInput,
  type OtpRequestInput,
  type OtpRequestResult,
  type OtpVerifyInput,
  type Session,
  type StaffLoginInput,
} from '@/lib/contract';
import { apiFetch } from './client';

/** Auth API calls (§6). `skipAuth` keeps the public ones off the access-token/refresh path. */

/** Staff sign-in: email + password. */
export function loginStaff(body: StaffLoginInput): Promise<AuthResult> {
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

export function requestOtp(body: OtpRequestInput): Promise<OtpRequestResult> {
  return apiFetch('/auth/request-otp', {
    method: 'POST',
    body,
    schema: OtpRequestResultSchema,
    skipAuth: true,
  });
}

export function verifyOtp(body: OtpVerifyInput): Promise<AuthResult> {
  return apiFetch('/auth/verify-otp', {
    method: 'POST',
    body,
    schema: AuthResultSchema,
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
