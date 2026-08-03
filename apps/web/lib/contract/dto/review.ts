import { z } from 'zod';

/**
 * HR verification & approval REQUEST contracts (ARCHITECTURE.md §3.3/§3.4). Response shapes
 * (EmployeeRecord, RecordSection, RecordDocument, DecisionResult) are derived from the Java OpenAPI schema
 * in `../responses.ts`.
 */

/** The lookup-by-ID form in the HR verification workspace. */
export const EmployeeLookupSchema = z.object({
  employeeCode: z.string().trim().toUpperCase().min(1, 'Enter an employee ID'),
});
export type EmployeeLookupInput = z.infer<typeof EmployeeLookupSchema>;

// REJECTED is kept for API compatibility but is not offered per-item in the UI (§3.3): the two
// surfaced actions are Verify and Send back for revision (REVISION_REQUESTED).
export const ReviewDecisionValues = ['VERIFIED', 'REJECTED', 'REVISION_REQUESTED'] as const;
export type ReviewDecision = (typeof ReviewDecisionValues)[number];

/** Verify / send back a form or document; the reason is recorded in the audit trail. */
export const ReviewSchema = z.object({
  decision: z.enum(ReviewDecisionValues),
  reason: z.string().trim().max(500, 'Keep it under 500 characters').optional(),
});
export type ReviewInput = z.infer<typeof ReviewSchema>;

/** The "Send back for revision" dialog — the note is REQUIRED and shown to the employee (§3.3). */
export const SendBackSchema = z.object({
  note: z
    .string()
    .trim()
    .min(1, 'Add a note telling the employee what to fix')
    .max(500, 'Keep it under 500 characters'),
});
export type SendBackInput = z.infer<typeof SendBackSchema>;

/**
 * The HR APPROVE dialog (§3.3): only an optional note. The team is NOT chosen — the server approves the
 * employee onto their onboarding-HR's team (the system's scoping rule).
 */
export const ApproveSchema = z.object({
  note: z.string().trim().max(500, 'Keep it under 500 characters').optional(),
});
export type ApproveInput = z.infer<typeof ApproveSchema>;

/** The HR REJECT dialog (§3.3): a terminal rejection — the note is REQUIRED and recorded in the audit. */
export const RejectSchema = z.object({
  note: z
    .string()
    .trim()
    .min(1, 'Add a note describing why the application is rejected')
    .max(500, 'Keep it under 500 characters'),
});
export type RejectInput = z.infer<typeof RejectSchema>;
