import { z } from 'zod';

/**
 * Employee onboarding REQUEST contract (ARCHITECTURE.md §3.2 / §5). HR initiates onboarding by
 * email; the system mints the unique company-scoped employee ID and emails it. Response shapes
 * (EmployeeSummary, OnboardEmployeeResult) are derived from the Java OpenAPI schema in
 * `../responses.ts`.
 */

export const OnboardEmployeeSchema = z.object({
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
});
export type OnboardEmployeeInput = z.infer<typeof OnboardEmployeeSchema>;
