import { z } from 'zod';
import { RequestType } from '../enums';

/**
 * HR/Accounts document-request REQUEST contracts — Accounts side (§8d). Response shapes
 * (DocumentRequestView, TeamRequestRow, pages) are derived from the OpenAPI schema in `../responses`.
 * The accountant's fulfilment files reuse the mail-attachment client validator (same server rules).
 */

export const SubmitRequestSchema = z.object({
  requestType: z.nativeEnum(RequestType),
  // The note (e.g. the period "Jan–Mar 2026") is optional; trimmed, capped to match the server.
  note: z.string().trim().max(2000, 'Keep the note under 2000 characters').optional(),
});
export type SubmitRequestInput = z.infer<typeof SubmitRequestSchema>;

/** Human labels for the request types (dropdown + rows). */
export const REQUEST_TYPE_LABELS: Record<RequestType, string> = {
  PAYSLIP: 'Payslip',
  SALARY_CERTIFICATE: 'Salary Certificate',
  FORM16: 'Form 16',
  TAX_DOCUMENT: 'Tax Document',
  OTHER: 'Other',
  RELIEVING_LETTER: 'Relieving Letter',
  EXPERIENCE_LETTER: 'Experience Letter',
};
