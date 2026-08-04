import type {
  AgreementType,
  CompleteAgreementResult,
  MyAgreementSummary,
  MyAgreementView,
  SendAgreementsResult,
} from '@/lib/contract';
import type { CompleteAgreementInput } from '@/lib/contract';
import { apiFetch } from './client';

/**
 * Post-approval agreements (§Agreements). HR sends the standard pack; the employee reads, fills, signs, and
 * submits each one under `/me/agreements`.
 */

/** HR: send the three-agreement pack to an APPROVED employee (idempotent; onboarding-HR-scoped). */
export function sendAgreements(employeeId: string): Promise<SendAgreementsResult> {
  return apiFetch<SendAgreementsResult>(
    `/employees/${encodeURIComponent(employeeId)}/agreements/send`,
    { method: 'POST' },
  );
}

/** Employee: my agreements (fixed pack order); empty until HR sends. */
export function getMyAgreements(signal?: AbortSignal): Promise<MyAgreementSummary[]> {
  return apiFetch<MyAgreementSummary[]>('/me/agreements', { signal });
}

/** Employee: one agreement to read and fill — full body text + prefills + status. */
export function getMyAgreement(type: AgreementType, signal?: AbortSignal): Promise<MyAgreementView> {
  return apiFetch<MyAgreementView>(`/me/agreements/${encodeURIComponent(type)}`, { signal });
}

/** Employee: sign and submit one agreement — renders + stores the PDF, marks it completed. */
export function completeAgreement(
  type: AgreementType,
  body: CompleteAgreementInput,
): Promise<CompleteAgreementResult> {
  return apiFetch<CompleteAgreementResult>(
    `/me/agreements/${encodeURIComponent(type)}/complete`,
    { method: 'POST', body },
  );
}
