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
  COMPLETED: 'Completed',
} as const;

/** The three offboarding document types, in send order, with titles (§3.6 stage 2). */
export const OFFBOARDING_DOC_TYPES = ['EXIT_FORMALITIES', 'SETTLEMENT', 'SEPARATION'] as const;

export const OFFBOARDING_DOC_TITLES: Record<(typeof OFFBOARDING_DOC_TYPES)[number], string> = {
  EXIT_FORMALITIES: 'Separation & Exit Formalities',
  SETTLEMENT: 'Settlement Agreement',
  SEPARATION: 'Employee Separation Agreement and Release',
};

/** Per-document status labels for the timeline. */
export const OFFBOARDING_DOC_STATUS_LABELS: Record<string, string> = {
  PENDING: 'To sign',
  SUBMITTED: 'Submitted — awaiting HR',
  VERIFIED: 'Verified',
  REVISION_REQUESTED: 'Sent back',
};
