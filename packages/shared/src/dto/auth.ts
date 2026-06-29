import { z } from 'zod';
import { UserRole } from '../enums';
import { EMPLOYEE_CODE_REGEX } from '../ids';

/**
 * Auth contracts (ARCHITECTURE.md §6) — shared by api (validation) and web (forms).
 * Staff log in with email + password; employees with employeeCode + email -> OTP.
 */

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

export const StaffLoginSchema = z.object({
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
  password: z.string().min(8, 'At least 8 characters'),
});
export type StaffLoginInput = z.infer<typeof StaffLoginSchema>;

export const EmployeeOtpRequestSchema = z.object({
  employeeCode: z
    .string()
    .trim()
    .toUpperCase()
    .regex(EMPLOYEE_CODE_REGEX, 'Enter a valid employee ID (e.g. ACME-EMP-000123)'),
  email: z.string().min(1, 'Email is required').email('Enter a valid email'),
});
export type EmployeeOtpRequestInput = z.infer<typeof EmployeeOtpRequestSchema>;

export const EmployeeOtpVerifySchema = z.object({
  employeeCode: z
    .string()
    .trim()
    .toUpperCase()
    .regex(EMPLOYEE_CODE_REGEX, 'Enter a valid employee ID'),
  otp: z
    .string()
    .trim()
    .regex(/^\d{6}$/, 'Enter the 6-digit code'),
});
export type EmployeeOtpVerifyInput = z.infer<typeof EmployeeOtpVerifySchema>;

// ---------------------------------------------------------------------------
// Responses
// ---------------------------------------------------------------------------

/** Who the caller is + the scope the UI uses to route. Mirrors the API `Principal`. */
export const SessionSchema = z.discriminatedUnion('type', [
  z.object({
    type: z.literal('USER'),
    userId: z.string(),
    email: z.string(),
    name: z.string(),
    role: z.nativeEnum(UserRole),
    companyId: z.string().nullable(),
    teamId: z.string().nullable(),
  }),
  z.object({
    type: z.literal('EMPLOYEE'),
    employeeId: z.string(),
    employeeCode: z.string(),
    email: z.string(),
    companyId: z.string(),
  }),
]);
export type Session = z.infer<typeof SessionSchema>;

/** Returned by /auth/login, /auth/employee/verify-otp and /auth/refresh. */
export const AuthResultSchema = z.object({
  accessToken: z.string(),
  session: SessionSchema,
});
export type AuthResult = z.infer<typeof AuthResultSchema>;

/** Returned by /auth/employee/request-otp. `devOtp` is present only in non-production. */
export const OtpRequestResultSchema = z.object({
  sent: z.boolean(),
  expiresInSeconds: z.number().int().positive(),
  devOtp: z.string().optional(),
});
export type OtpRequestResult = z.infer<typeof OtpRequestResultSchema>;
