import { z } from 'zod';
import { UserRole } from '../enums';

/**
 * Team management REQUEST contracts (ARCHITECTURE.md §2/§3.1). Response shapes are derived from
 * the Java OpenAPI schema in `../responses.ts`; only the request/form zod schemas live here.
 */

export const CreateTeamSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
});
export type CreateTeamInput = z.infer<typeof CreateTeamSchema>;

export const UpdateTeamSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
});
export type UpdateTeamInput = z.infer<typeof UpdateTeamSchema>;

/** Which slot is being assigned (§2: a team holds one HR, one Manager, one Accountant). */
export const TeamRoleSchema = z.enum([UserRole.HR, UserRole.MANAGER, UserRole.ACCOUNTANT]);
export type TeamRole = z.infer<typeof TeamRoleSchema>;

/** Attach an existing company user to the slot. */
export const AssignExistingMemberSchema = z.object({
  userId: z.string().min(1, 'Select a person'),
});

/** Create a new staff user for the slot (with an initial email + password sign-in, §6). */
export const AssignNewMemberSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
  password: z.string().min(8, 'Use at least 8 characters'),
});

/** Assign a slot by selecting an existing user OR creating a new one. */
export const AssignMemberSchema = z.union([AssignExistingMemberSchema, AssignNewMemberSchema]);
export type AssignMemberInput = z.infer<typeof AssignMemberSchema>;
export type AssignExistingMemberInput = z.infer<typeof AssignExistingMemberSchema>;
export type AssignNewMemberInput = z.infer<typeof AssignNewMemberSchema>;
