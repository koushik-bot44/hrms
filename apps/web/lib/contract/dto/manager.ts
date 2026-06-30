import { z } from 'zod';

/**
 * Manager inbox REQUEST contracts (ARCHITECTURE.md §2/§3.3). Response shapes (NotificationFeed,
 * NotificationItem, Approval) are derived from the Java OpenAPI schema in `../responses.ts`.
 */

/** Rejecting an approval requires a note. */
export const RejectApprovalSchema = z.object({
  note: z
    .string()
    .trim()
    .min(1, 'A note is required when rejecting')
    .max(500, 'Keep it under 500 characters'),
});
export type RejectApprovalInput = z.infer<typeof RejectApprovalSchema>;
