import { z } from 'zod';
import { EmployeeStatus } from '../enums';

/**
 * Employee onboarding contracts (ARCHITECTURE.md §3.2 / §5). HR initiates onboarding by
 * email; the system mints the unique company-scoped employee ID and emails it.
 */

export const OnboardEmployeeSchema = z.object({
  email: z.string().trim().toLowerCase().min(1, 'Email is required').email('Enter a valid email'),
});
export type OnboardEmployeeInput = z.infer<typeof OnboardEmployeeSchema>;

export const EmployeeSummarySchema = z.object({
  id: z.string(),
  employeeCode: z.string(),
  email: z.string(),
  status: z.nativeEnum(EmployeeStatus),
  createdAt: z.string(),
});
export type EmployeeSummary = z.infer<typeof EmployeeSummarySchema>;

export const EmployeeListSchema = z.array(EmployeeSummarySchema);

/** Returned by POST /employees — the new record plus the login link that was emailed. */
export const OnboardEmployeeResultSchema = z.object({
  employee: EmployeeSummarySchema,
  loginUrl: z.string(),
});
export type OnboardEmployeeResult = z.infer<typeof OnboardEmployeeResultSchema>;
