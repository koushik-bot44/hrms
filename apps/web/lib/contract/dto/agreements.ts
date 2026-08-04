import { z } from 'zod';

/**
 * Post-approval agreements (§Agreements) REQUEST contracts. Response shapes (AgreementSummary,
 * MyAgreementView, CompleteAgreementResult, …) are derived from the Java OpenAPI schema in `../responses.ts`.
 */

/** The three standard agreement types, in the order they are sent. */
export const AgreementTypeValues = ['AUP', 'NDA', 'NOTICE_PERIOD'] as const;

/** Human titles for the agreement list / cards. */
export const AGREEMENT_TITLES: Record<(typeof AgreementTypeValues)[number], string> = {
  AUP: 'Acceptable Use Policy (AUP)',
  NDA: 'Non-Disclosure & Non-Compete Agreement',
  NOTICE_PERIOD: 'Notice Period Conduct Guidelines',
};

/**
 * The employee's submission for one agreement. Only the fields relevant to the type are used (AUP →
 * designation + aadhaar; NDA → designation/address/mobile; Notice → none). Consent must be true; the Aadhaar
 * 12-digit rule is enforced per-type in the fill screen (AUP only) and re-checked server-side.
 */
export const CompleteAgreementSchema = z.object({
  consentAccepted: z.literal(true),
  designation: z.string().trim().max(150).optional(),
  aadhaar: z.string().trim().optional(),
  address: z.string().trim().max(300).optional(),
  mobile: z.string().trim().max(30).optional(),
  signatureDataUrl: z.string().min(1, 'A signature is required'),
});
export type CompleteAgreementInput = z.infer<typeof CompleteAgreementSchema>;

/** Aadhaar is exactly 12 digits (spaces/dashes stripped) — AUP only. */
export function isValidAadhaar(raw: string): boolean {
  return /^\d{12}$/.test(raw.replace(/[\s-]/g, ''));
}
