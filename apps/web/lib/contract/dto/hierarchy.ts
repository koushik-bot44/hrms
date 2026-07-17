import { z } from 'zod';
import { MailLocalPartSchema } from './mail';

/**
 * Hierarchy REQUEST contract (ARCHITECTURE.md §2/§6). The single cross-platform, read-only,
 * aggregates-only Hierarchy is provisioned by the Super Admin (name + mailbox local part + initial staff
 * password); the local part forms localPart@ihrms, which IS the login email (§8). Response shapes
 * (HierarchyStatus, ProvisionHierarchyResult) are derived from the Java OpenAPI schema in `../responses.ts`.
 */
export const ProvisionHierarchySchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  localPart: MailLocalPartSchema,
  // Staff sign in with email + password (§6): the Super Admin sets an initial password (min 8).
  password: z.string().min(8, 'Use at least 8 characters'),
});
export type ProvisionHierarchyInput = z.infer<typeof ProvisionHierarchySchema>;
