import { z } from 'zod';
import { COMPANY_CODE_REGEX } from '../ids';

/**
 * Company management contracts (ARCHITECTURE.md §2/§3.1) — Super Admin creates/manages
 * companies and provisions each company's admin.
 *
 * NOTE: company `status` is a free string column (not one of the 8 domain enums), so the
 * allowed values are constrained here with a zod enum — NOT added to SHARED_ENUMS, which
 * stays at exactly 8 (enum-parity test).
 */

export const CompanyStatusValues = ['ACTIVE', 'SUSPENDED'] as const;
export const CompanyStatusSchema = z.enum(CompanyStatusValues);
export type CompanyStatus = z.infer<typeof CompanyStatusSchema>;

/** Short mnemonic used in employee IDs — uppercase, starts with a letter, 2–16 chars (§5). */
export const CompanyCodeSchema = z
  .string()
  .trim()
  .toUpperCase()
  .regex(COMPANY_CODE_REGEX, 'Code must be 2–16 uppercase letters/digits, starting with a letter');

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

export const CreateCompanySchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  code: CompanyCodeSchema,
});
export type CreateCompanyInput = z.infer<typeof CreateCompanySchema>;

export const UpdateCompanySchema = z
  .object({
    name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long').optional(),
    status: CompanyStatusSchema.optional(),
  })
  .refine((value) => value.name !== undefined || value.status !== undefined, {
    message: 'Provide a name or a status to update',
  });
export type UpdateCompanyInput = z.infer<typeof UpdateCompanySchema>;

export const ProvisionCompanyAdminSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
});
export type ProvisionCompanyAdminInput = z.infer<typeof ProvisionCompanyAdminSchema>;

// ---------------------------------------------------------------------------
// Responses
// ---------------------------------------------------------------------------

export const CompanyAdminSchema = z.object({
  id: z.string(),
  email: z.string(),
  name: z.string(),
  status: z.string(),
  createdAt: z.string(),
});
export type CompanyAdmin = z.infer<typeof CompanyAdminSchema>;

export const CompanySummarySchema = z.object({
  id: z.string(),
  name: z.string(),
  code: z.string(),
  status: CompanyStatusSchema,
  teamCount: z.number().int(),
  employeeCount: z.number().int(),
  hasAdmin: z.boolean(),
  createdAt: z.string(),
});
export type CompanySummary = z.infer<typeof CompanySummarySchema>;

export const CompanyListSchema = z.array(CompanySummarySchema);

export const CompanyDetailSchema = CompanySummarySchema.extend({
  admin: CompanyAdminSchema.nullable(),
});
export type CompanyDetail = z.infer<typeof CompanyDetailSchema>;

/** `devPassword` is present only in non-production (mirrors the OTP dev affordance). */
export const ProvisionCompanyAdminResultSchema = z.object({
  admin: CompanyAdminSchema,
  devPassword: z.string().optional(),
});
export type ProvisionCompanyAdminResult = z.infer<typeof ProvisionCompanyAdminResultSchema>;
