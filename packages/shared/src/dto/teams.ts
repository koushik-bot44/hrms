import { z } from 'zod';
import { UserRole } from '../enums';

/**
 * Team management contracts (ARCHITECTURE.md §2/§3.1) — a Company Admin creates teams
 * within their own company and assigns exactly one HR and one Manager to each.
 */

// ---------------------------------------------------------------------------
// Requests
// ---------------------------------------------------------------------------

export const CreateTeamSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
});
export type CreateTeamInput = z.infer<typeof CreateTeamSchema>;

export const UpdateTeamSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
});
export type UpdateTeamInput = z.infer<typeof UpdateTeamSchema>;

/** Which slot is being assigned. */
export const TeamRoleSchema = z.enum([UserRole.HR, UserRole.MANAGER]);
export type TeamRole = z.infer<typeof TeamRoleSchema>;

/** Attach an existing company user to the slot. */
export const AssignExistingMemberSchema = z.object({
  userId: z.string().min(1, 'Select a person'),
});

/** Create a new staff user for the slot (initial credentials emailed). */
export const AssignNewMemberSchema = z.object({
  name: z.string().trim().min(2, 'Name is required').max(120, 'Name is too long'),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
});

/** Assign a slot by selecting an existing user OR creating a new one. */
export const AssignMemberSchema = z.union([AssignExistingMemberSchema, AssignNewMemberSchema]);
export type AssignMemberInput = z.infer<typeof AssignMemberSchema>;
export type AssignExistingMemberInput = z.infer<typeof AssignExistingMemberSchema>;
export type AssignNewMemberInput = z.infer<typeof AssignNewMemberSchema>;

// ---------------------------------------------------------------------------
// Responses
// ---------------------------------------------------------------------------

export const TeamMemberSchema = z.object({
  id: z.string(),
  name: z.string(),
  email: z.string(),
  role: z.nativeEnum(UserRole),
  status: z.string(),
});
export type TeamMember = z.infer<typeof TeamMemberSchema>;

export const TeamSummarySchema = z.object({
  id: z.string(),
  name: z.string(),
  hr: TeamMemberSchema.nullable(),
  manager: TeamMemberSchema.nullable(),
  memberCount: z.number().int(),
  createdAt: z.string(),
});
export type TeamSummary = z.infer<typeof TeamSummarySchema>;

export const TeamListSchema = z.array(TeamSummarySchema);

export const TeamDetailSchema = TeamSummarySchema.extend({
  members: z.array(TeamMemberSchema),
});
export type TeamDetail = z.infer<typeof TeamDetailSchema>;

export const AssignableUserSchema = TeamMemberSchema;
export type AssignableUser = z.infer<typeof AssignableUserSchema>;
export const AssignableUserListSchema = z.array(AssignableUserSchema);

/** `devPassword` is present only when a new user was created in non-production. */
export const AssignMemberResultSchema = z.object({
  team: TeamDetailSchema,
  devPassword: z.string().optional(),
});
export type AssignMemberResult = z.infer<typeof AssignMemberResultSchema>;
