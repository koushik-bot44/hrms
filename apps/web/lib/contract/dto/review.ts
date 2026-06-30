import { z } from 'zod';

/**
 * HR verification & routing REQUEST contracts (ARCHITECTURE.md §3.3/§3.4). Response shapes
 * (EmployeeRecord, RecordSection, RecordDocument, RouteToManagerResult) are derived from the Java
 * OpenAPI schema in `../responses.ts`.
 */

/** The lookup-by-ID form in the HR verification workspace. */
export const EmployeeLookupSchema = z.object({
  employeeCode: z.string().trim().toUpperCase().min(1, 'Enter an employee ID'),
});
export type EmployeeLookupInput = z.infer<typeof EmployeeLookupSchema>;

export const ReviewDecisionValues = ['VERIFIED', 'REJECTED'] as const;
export type ReviewDecision = (typeof ReviewDecisionValues)[number];

/** Verify or reject a section/document; the optional reason is recorded in the audit trail. */
export const ReviewSchema = z.object({
  decision: z.enum(ReviewDecisionValues),
  reason: z.string().trim().max(500, 'Keep it under 500 characters').optional(),
});
export type ReviewInput = z.infer<typeof ReviewSchema>;

/** The reject dialog (optional reason). */
export const RejectReasonSchema = z.object({
  reason: z.string().trim().max(500, 'Keep it under 500 characters').optional(),
});
export type RejectReasonInput = z.infer<typeof RejectReasonSchema>;

/** The route-to-Manager confirm dialog (optional note). */
export const RouteToManagerSchema = z.object({
  note: z.string().trim().max(500, 'Keep it under 500 characters').optional(),
});
export type RouteToManagerInput = z.infer<typeof RouteToManagerSchema>;
