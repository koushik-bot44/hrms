import { z } from 'zod';
import { COMPANY_CODE_REGEX } from '../ids';
import { MailDomainSchema, MailLocalPartSchema } from './mail';

/**
 * Company management REQUEST contracts (ARCHITECTURE.md §2/§3.1). Response shapes are derived
 * from the Java OpenAPI schema in `../responses.ts`; only the request/form zod schemas (used by
 * react-hook-form) live here.
 *
 * NOTE: company `status` is a free string column (not one of the 8 domain enums), so the allowed
 * values are constrained here with a zod enum — NOT added to SHARED_ENUMS.
 */

// DELETED is the archived (soft-delete) state — reached via the delete endpoint, not update.
export const CompanyStatusValues = ['ACTIVE', 'SUSPENDED', 'DELETED'] as const;
export const CompanyStatusSchema = z.enum(CompanyStatusValues);
export type CompanyStatus = z.infer<typeof CompanyStatusSchema>;

/** Short mnemonic used in employee IDs — uppercase, starts with a letter, 2–16 chars (§5). */
export const CompanyCodeSchema = z
  .string()
  .trim()
  .toUpperCase()
  .regex(COMPANY_CODE_REGEX, 'Code must be 2–16 uppercase letters/digits, starting with a letter');

export const CreateCompanySchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  code: CompanyCodeSchema,
  // The company's internal-mail domain (§8) — prefilled from the code, editable, unique across companies.
  mailDomain: MailDomainSchema,
});
export type CreateCompanyInput = z.infer<typeof CreateCompanySchema>;

export const UpdateCompanySchema = z
  .object({
    name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long').optional(),
    // Archival/restore is via the delete/restore endpoints, not update.
    status: z.enum(['ACTIVE', 'SUSPENDED']).optional(),
  })
  .refine((value) => value.name !== undefined || value.status !== undefined, {
    message: 'Provide a name or a status to update',
  });
export type UpdateCompanyInput = z.infer<typeof UpdateCompanySchema>;

export const ProvisionCompanyAdminSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  // The mailbox local part forms localPart@companyDomain, which IS the admin's login email (§8).
  localPart: MailLocalPartSchema,
  // Staff sign in with email + password (§6): the admin sets an initial password (min 8).
  password: z.string().min(8, 'Use at least 8 characters'),
});
export type ProvisionCompanyAdminInput = z.infer<typeof ProvisionCompanyAdminSchema>;

// --- Per-company letterhead (§3.5) -----------------------------------------

/** One stored letterhead part's metadata + a short-lived presigned preview URL (null when not previewable). */
export interface LetterheadPart {
  width: number;
  height: number;
  contentType: string | null;
  previewUrl: string | null;
}

/** A company's current letterhead: header/footer parts (null when unset) + when it last changed. */
export interface Letterhead {
  header: LetterheadPart | null;
  footer: LetterheadPart | null;
  updatedAt: string | null;
}

/** The presigned PUT handshake for a letterhead part (same shape as the mail-attachment upload). */
export interface LetterheadUpload {
  uploadUrl: string;
  method: string;
  headers: Record<string, string>;
  expiresInSeconds: number;
  part: string;
}

/** Which band a letterhead image is for. */
export type LetterheadPartName = 'header' | 'footer';

/** Client-side upload rules — mirror LetterheadService (server re-validates authoritatively). */
export const LETTERHEAD_MAX_BYTES = 5 * 1024 * 1024;
export const LETTERHEAD_MIN_WIDTH_PX = 1000;
export const LETTERHEAD_ACCEPT = ['image/png', 'image/jpeg'] as const;
