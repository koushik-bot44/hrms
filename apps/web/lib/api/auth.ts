import {
  AuthResultSchema,
  OtpRequestResultSchema,
  SessionSchema,
  type AuthResult,
  type OtpRequestInput,
  type OtpRequestResult,
  type OtpVerifyInput,
  type Session,
} from '@/lib/contract';
import { apiFetch } from './client';

/** Unified auth API calls (§6). `skipAuth` keeps these off the access-token/refresh path. */

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
