import { z } from 'zod';
import { LeaveType } from '../enums';

/**
 * Leave request REQUEST contracts (§8b). Response shapes (LeaveRequest, TeamLeaveRow, pages) are derived
 * from the Java OpenAPI schema in `../responses.ts`. No leave balances in v1.
 */

/** The "Request leave" form. `endDate >= startDate` (the server re-checks). Dates are ISO yyyy-MM-dd. */
export const SubmitLeaveSchema = z
  .object({
    startDate: z.string().min(1, 'Pick a start date'),
    endDate: z.string().min(1, 'Pick an end date'),
    leaveType: z.nativeEnum(LeaveType),
    reason: z.string().trim().min(1, 'Add a reason').max(2000, 'Keep it under 2000 characters'),
  })
  .refine((v) => v.endDate >= v.startDate, {
    message: 'End date must be on or after the start date',
    path: ['endDate'],
  });
export type SubmitLeaveInput = z.infer<typeof SubmitLeaveSchema>;

/** The Manager's reject dialog — a note is REQUIRED (§8b). */
export const RejectLeaveSchema = z.object({
  note: z.string().trim().min(1, 'A note is required to reject').max(2000, 'Keep it under 2000 characters'),
});
export type RejectLeaveInput = z.infer<typeof RejectLeaveSchema>;
