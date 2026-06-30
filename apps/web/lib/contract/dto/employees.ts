import { z } from 'zod';

/**
 * Employee onboarding REQUEST contract (ARCHITECTURE.md §3.2). HR onboards with full name, email,
 * designation (job title) and date of joining. No employee ID is minted here — it is allocated on
 * Manager approval (§5). Response shapes (EmployeeSummary, OnboardEmployeeResult) are derived from
 * the Java OpenAPI schema in `../responses.ts`.
 */

export const OnboardEmployeeSchema = z.object({
  fullName: z.string().trim().min(2, 'Full name is required').max(120),
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
  designation: z.string().trim().min(2, 'Designation is required').max(120),
  dateOfJoining: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Select a date of joining'),
});
export type OnboardEmployeeInput = z.infer<typeof OnboardEmployeeSchema>;
