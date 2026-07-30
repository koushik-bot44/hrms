import { z } from 'zod';
import { UserRole } from '../enums';

/**
 * Auth contracts (ARCHITECTURE.md §6) — shared by api (validation) and web (forms). Two audiences:
 * STAFF sign in with email + password ({@link StaffLoginSchema}) at /login; EMPLOYEES sign in with
 * full name + email -> OTP ({@link OtpRequestSchema}/{@link OtpVerifySchema}) at /employee/login.
 * Staff passwords are self-service changeable ({@link ChangePasswordSchema}).
 */

/** Minimum staff-password length (mirrors the API). */
export const MIN_PASSWORD_LENGTH = 8;

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

/**
 * Sign-in at /login: a login identifier + password. The identifier is a staff email OR an internal
 * mailbox address (§8) like {@code arjun@anvicorp} — the latter has no TLD, so we accept any
 * {@code localpart@domain} handle rather than a strict RFC email (the server resolves it).
 */
export const StaffLoginSchema = z.object({
  email: z
    .string()
    .trim()
    .toLowerCase()
    .min(1, 'Email is required')
    // Accept a mailbox address (localpart@domain, no TLD) too — the server resolves it.
    .regex(/^[^\s@]+@[^\s@]+$/, 'Enter a valid email'),
  password: z.string().min(1, 'Password is required'),
});
export type StaffLoginInput = z.infer<typeof StaffLoginSchema>;

/** Staff self-service password change. */
export const ChangePasswordSchema = z.object({
  currentPassword: z.string().min(1, 'Your current password is required'),
  newPassword: z.string().min(MIN_PASSWORD_LENGTH, `Use at least ${MIN_PASSWORD_LENGTH} characters`),
});
export type ChangePasswordInput = z.infer<typeof ChangePasswordSchema>;

/** Employee sign-in start: full name + email -> OTP. */
export const OtpRequestSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter your full name').max(120),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
});
export type OtpRequestInput = z.infer<typeof OtpRequestSchema>;

export const OtpVerifySchema = z.object({
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
  otp: z
    .string()
    .trim()
    .regex(/^\d{6}$/, 'Enter the 6-digit code'),
});
export type OtpVerifyInput = z.infer<typeof OtpVerifySchema>;

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
    // The company's URL slug (Stage 2 routing); null for platform roles (SUPER_ADMIN/ACCOUNTS_ADMIN/HIERARCHY).
    companySlug: z.string().nullable(),
  }),
  z.object({
    type: z.literal('EMPLOYEE'),
    employeeId: z.string(),
    // null until the employee is approved (the ID is allocated on Manager approval, §5).
    employeeCode: z.string().nullable(),
    email: z.string(),
    companyId: z.string(),
    // Populated once the employee has internal credentials (§8, Stage 5); null before then. Their
    // mailbox address drives the Mail button + "own address"; name is their full name.
    name: z.string().nullish(),
    mailAddress: z.string().nullish(),
    // Which sign-in door was used (Stage 6): PASSWORD -> employee portal, OTP -> onboarding. Carried in
    // the refresh token so a page refresh keeps the same landing. Null (legacy) is treated as onboarding.
    authMethod: z.enum(['PASSWORD', 'OTP']).nullish(),
    // The company's URL slug (Stage 2 routing) — an employee is always tied to a company.
    companySlug: z.string().nullable(),
  }),
]);
export type Session = z.infer<typeof SessionSchema>;

/** Returned by /auth/verify-otp and /auth/refresh. */
export const AuthResultSchema = z.object({
  accessToken: z.string(),
  session: SessionSchema,
});
export type AuthResult = z.infer<typeof AuthResultSchema>;

/** Returned by /auth/request-otp. `devOtp` is present only in non-production. */
export const OtpRequestResultSchema = z.object({
  sent: z.boolean(),
  expiresInSeconds: z.number().int().positive(),
  devOtp: z.string().optional(),
});
export type OtpRequestResult = z.infer<typeof OtpRequestResultSchema>;
