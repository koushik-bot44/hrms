import { z } from 'zod';
import { Form2Schema } from './onboarding';
import { MailLocalPartSchema } from './mail';

/**
 * Employee onboarding REQUEST contract (ARCHITECTURE.md §3.2). Onboarding = HR/SA fills FORM 2 —
 * Employee Info (the shared {@link Form2Schema}); submitting it creates the record and sends the invite
 * to the personal email. No employee ID is minted here — it is allocated on Manager approval (§5).
 * Response shapes (EmployeeSummary, OnboardEmployeeResult) are derived from the Java OpenAPI schema in
 * `../responses.ts`.
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

/** Onboarding = fill Form 2 (§3.2). The HR onboard form IS the Employee-Info form. */
export const OnboardEmployeeSchema = Form2Schema;
export type OnboardEmployeeInput = z.infer<typeof OnboardEmployeeSchema>;

/**
 * SUPER_ADMIN onboarding form (§2): pick company → team (which determines the HR) → fill Form 2.
 * `companyId` addresses the endpoint (`/companies/{companyId}/employees`); `teamId` + the Form-2 body.
 */
export const SuperAdminOnboardSchema = Form2Schema.extend({
  companyId: z.string().min(1, 'Select a company'),
  teamId: z.string().min(1, 'Select a team'),
});
export type SuperAdminOnboardInput = z.infer<typeof SuperAdminOnboardSchema>;
