import { z } from 'zod';
import { MailLocalPartSchema } from './mail';

/**
 * Accountant REQUEST contracts (ARCHITECTURE.md §2/§6). The Accounts Admin is provisioned by the Super
 * Admin (name + mailbox local part + initial staff password); the local part forms localPart@ihrms,
 * which IS the login email (§8). Response shapes (AccountantStatus, ApprovedEmployeePage,
 * EmployeeRecord, RevealedSensitive, AuditLogPage) are derived from the Java OpenAPI schema in
 * `../responses.ts`.
 */
export const ProvisionAccountantSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  localPart: MailLocalPartSchema,
  // Staff sign in with email + password (§6): the Super Admin sets an initial password (min 8).
  password: z.string().min(8, 'Use at least 8 characters'),
});
export type ProvisionAccountantInput = z.infer<typeof ProvisionAccountantSchema>;
