import { z } from 'zod';

/**
 * Offboarding (§Offboarding, stage 1) REQUEST contracts. Response shapes (OffboardingCaseView,
 * HierarchyPendingRow, …) are derived from the Java OpenAPI schema in `../responses.ts`.
 */

/** HR initiates an offboarding case — reason + last working day (ISO date). */
export const InitiateOffboardingSchema = z.object({
  reason: z.string().trim().min(1, 'A reason is required').max(1000, 'Keep it under 1000 characters'),
  lastWorkingDay: z.string().min(1, 'Choose the last working day'),
});
export type InitiateOffboardingInput = z.infer<typeof InitiateOffboardingSchema>;

/** A human label for each offboarding status. */
export const OFFBOARDING_STATUS_LABELS = {
  PENDING_APPROVAL: 'Awaiting approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
} as const;
