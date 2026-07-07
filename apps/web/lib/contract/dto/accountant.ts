import { z } from 'zod';

/**
 * Accountant REQUEST contracts (ARCHITECTURE.md §2/§6). The Accountant is provisioned by the Super
 * Admin (email + name + initial staff password). Response shapes (AccountantStatus,
 * ApprovedEmployeePage, EmployeeRecord, RevealedSensitive, AuditLogPage) are derived from the Java
 * OpenAPI schema in `../responses.ts`.
 */
export const ProvisionAccountantSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
  // Staff sign in with email + password (§6): the Super Admin sets an initial password (min 8).
  password: z.string().min(8, 'Use at least 8 characters'),
});
export type ProvisionAccountantInput = z.infer<typeof ProvisionAccountantSchema>;
