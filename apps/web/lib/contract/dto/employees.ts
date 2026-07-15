import { z } from 'zod';
import { istTodayIso } from '@/lib/date';
import { MailLocalPartSchema } from './mail';

const DATE_ISO = /^\d{4}-\d{2}-\d{2}$/;

/**
 * Employee onboarding REQUEST contract (ARCHITECTURE.md §3.2). HR onboards with full name, email,
 * designation (job title) and date of joining. No employee ID is minted here — it is allocated on
 * Manager approval (§5). Response shapes (EmployeeSummary, OnboardEmployeeResult) are derived from
 * the Java OpenAPI schema in `../responses.ts`.
 */

/**
 * HR assigns an APPROVED employee internal credentials (§8, Stage 5): a mailbox local part (→
 * `localpart@companyDomain`) + a password. The password is pre-filled with a generated one (the
 * default) but HR may type their own.
 */
export const AssignCredentialsSchema = z.object({
  localPart: MailLocalPartSchema,
  password: z.string().min(8, 'Use at least 8 characters'),
});
export type AssignCredentialsInput = z.infer<typeof AssignCredentialsSchema>;

export const OnboardEmployeeSchema = z.object({
  fullName: z.string().trim().min(2, 'Full name is required').max(120),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
  designation: z.string().trim().min(2, 'Designation is required').max(120),
  // Must be today or a future date — you can't onboard someone with a past joining date.
  dateOfJoining: z
    .string()
    .regex(DATE_ISO, 'Select a date of joining')
    .refine((v) => !DATE_ISO.test(v) || v >= istTodayIso(), 'Date of joining must be today or later'),
});
export type OnboardEmployeeInput = z.infer<typeof OnboardEmployeeSchema>;

/**
 * SUPER_ADMIN onboarding form (§2): pick company → team (which determines the HR) → employee fields.
 * `companyId` addresses the endpoint (`/companies/{companyId}/employees`); `teamId` + the four fields
 * are the body.
 */
export const SuperAdminOnboardSchema = OnboardEmployeeSchema.extend({
  companyId: z.string().min(1, 'Select a company'),
  teamId: z.string().min(1, 'Select a team'),
});
export type SuperAdminOnboardInput = z.infer<typeof SuperAdminOnboardSchema>;
