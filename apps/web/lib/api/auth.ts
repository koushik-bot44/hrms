import {
  AuthResultSchema,
  OtpRequestResultSchema,
  SessionSchema,
  type AuthResult,
  type EmployeeOtpRequestInput,
  type EmployeeOtpVerifyInput,
  type OtpRequestResult,
  type Session,
  type StaffLoginInput,
} from '@/lib/contract';
import { apiFetch } from './client';

/** Auth API calls. `skipAuth` keeps these off the access-token/refresh path. */

export function loginStaff(body: StaffLoginInput): Promise<AuthResult> {
  return apiFetch('/auth/login', { method: 'POST', body, schema: AuthResultSchema, skipAuth: true });
}

export function requestEmployeeOtp(body: EmployeeOtpRequestInput): Promise<OtpRequestResult> {
  return apiFetch('/auth/employee/request-otp', {
    method: 'POST',
    body,
    schema: OtpRequestResultSchema,
    skipAuth: true,
  });
}

export function verifyEmployeeOtp(body: EmployeeOtpVerifyInput): Promise<AuthResult> {
  return apiFetch('/auth/employee/verify-otp', {
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
