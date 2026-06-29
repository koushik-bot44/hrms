import { z } from 'zod';
import {
  Role,
  EmploymentType,
  WorkAuthStatus,
  OnboardingState,
} from '../enums';

const enumValues = <T extends Record<string, string>>(e: T) =>
  Object.values(e) as [string, ...string[]];

/**
 * Request contract for creating a User within an Entity.
 * Enum-typed fields validate against the shared enums — the same source of truth
 * the Prisma DB enums are checked against.
 */
export const CreateUserSchema = z.object({
  email: z.string().email(),
  fullName: z.string().min(1).max(200),
  role: z.enum(enumValues(Role)).default(Role.EMPLOYEE),
  employmentType: z.enum(enumValues(EmploymentType)).optional(),
  workAuthStatus: z.enum(enumValues(WorkAuthStatus)).optional(),
  onboardingState: z.enum(enumValues(OnboardingState)).default(OnboardingState.INVITED),
});

export type CreateUserDto = z.infer<typeof CreateUserSchema>;
