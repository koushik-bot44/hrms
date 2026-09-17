import { z } from 'zod';
import { istTodayIso } from '../../date';
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

/**
 * The Offer Letter terms HR provides at invite (§3.2). The offer opens onboarding — the invited employee must
 * accept it before any form unlocks. `salary` is free text so HR controls the wording (seeded
 * "X,XX,XXX Per Annum"); `location` defaults to Hyderabad. Joining date / designation / name are reused from
 * the Form-2 payload — not duplicated here.
 */
export const OfferTermsSchema = z.object({
  salary: z.string().trim().min(1, 'Salary is required').max(200, 'Salary is too long'),
  location: z.string().trim().max(120, 'Location is too long').optional(),
});
export type OfferTermsInput = z.infer<typeof OfferTermsSchema>;

/** Onboarding = fill Form 2 (§3.2) + the OFFER terms. The HR onboard form is Employee-Info + the offer. */
export const OnboardEmployeeSchema = Form2Schema.extend(OfferTermsSchema.shape);
export type OnboardEmployeeInput = z.infer<typeof OnboardEmployeeSchema>;

/**
 * SUPER_ADMIN onboarding form (§2): pick company → team (which determines the HR) → fill Form 2 + the offer.
 * `companyId` addresses the endpoint (`/companies/{companyId}/employees`); `teamId` + the Form-2 + offer body.
 */
export const SuperAdminOnboardSchema = Form2Schema.extend(OfferTermsSchema.shape).extend({
  companyId: z.string().min(1, 'Select a company'),
  teamId: z.string().min(1, 'Select a team'),
});
export type SuperAdminOnboardInput = z.infer<typeof SuperAdminOnboardSchema>;

/**
 * An EXISTING employee's joining date (§3.2): they already work here, so any past date is valid — but not a
 * future one (today in IST is the latest). Compared as yyyy-MM-dd strings once the Form-2 format check passes.
 */
const ExistingDateOfJoiningSchema = Form2Schema.shape.dateOfJoining.refine(
  (v) => v <= istTodayIso(),
  'Date of joining cannot be in the future',
);

/**
 * "Existing employee" onboarding (§3.2): someone who already works at the company but has no record. HR fills
 * Form 2 — no offer terms, no offer letter, no email — including the employee ID and official email they
 * ALREADY have (kept on approval, never minted). HR then enters the rest of their record and approves.
 * Body: `POST /employees/existing` `{ form2 }`.
 */
export const OnboardExistingEmployeeSchema = Form2Schema.extend({
  dateOfJoining: ExistingDateOfJoiningSchema,
  employeeId: z
    .string()
    .trim()
    .min(1, 'Employee ID is required')
    .max(40, 'Employee ID is too long')
    .regex(/^[A-Za-z0-9][A-Za-z0-9._-]*$/, 'Use letters, numbers, and . _ -'),
  officialEmail: z
    .string()
    .trim()
    .toLowerCase()
    .min(1, 'Official email is required')
    .email('Enter a valid email')
    .max(180),
});
export type OnboardExistingEmployeeInput = z.infer<typeof OnboardExistingEmployeeSchema>;

/**
 * SUPER_ADMIN variant of the existing-employee onboard (§2): company → team → Form 2 only.
 * Body: `POST /companies/{companyId}/employees/existing` `{ teamId, form2 }`.
 */
export const SuperAdminOnboardExistingSchema = OnboardExistingEmployeeSchema.extend({
  companyId: z.string().min(1, 'Select a company'),
  teamId: z.string().min(1, 'Select a team'),
});
export type SuperAdminOnboardExistingInput = z.infer<typeof SuperAdminOnboardExistingSchema>;
