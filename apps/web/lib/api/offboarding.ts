import type {
  HierarchyPendingRow,
  OffboardingCaseResponse,
  OffboardingCaseView,
  OffboardingDecisionResult,
} from '@/lib/contract';
import type { InitiateOffboardingInput } from '@/lib/contract';
import { apiFetch } from './client';

/** Offboarding lifecycle, stage 1 (§Offboarding). HR initiate/cancel + the case read; HIERARCHY approvals. */

/** HR: initiate an offboarding case for an APPROVED employee (own-scope). */
export function initiateOffboarding(
  employeeId: string,
  body: InitiateOffboardingInput,
): Promise<OffboardingCaseView> {
  return apiFetch<OffboardingCaseView>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/initiate`,
    { method: 'POST', body },
  );
}

/** HR / record viewers: the employee's latest case (or null). */
export function getOffboardingCase(
  employeeId: string,
  signal?: AbortSignal,
): Promise<OffboardingCaseResponse> {
  return apiFetch<OffboardingCaseResponse>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding`,
    { signal },
  );
}

/** HR: cancel the active case (pre-completion). */
export function cancelOffboarding(
  employeeId: string,
  note?: string,
): Promise<OffboardingCaseView> {
  return apiFetch<OffboardingCaseView>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/cancel`,
    { method: 'POST', body: { note: note ?? null } },
  );
}

/** HIERARCHY: all companies' pending cases (minimal-PII). */
export function getPendingOffboarding(signal?: AbortSignal): Promise<HierarchyPendingRow[]> {
  return apiFetch<HierarchyPendingRow[]>('/hierarchy/offboarding/pending', { signal });
}

/** HIERARCHY: approve a pending case (note optional). */
export function approveOffboarding(
  caseId: string,
  note?: string,
): Promise<OffboardingDecisionResult> {
  return apiFetch<OffboardingDecisionResult>(
    `/hierarchy/offboarding/${encodeURIComponent(caseId)}/approve`,
    { method: 'POST', body: { note: note ?? null } },
  );
}

/** HIERARCHY: reject a pending case (note required). */
export function rejectOffboarding(
  caseId: string,
  note: string,
): Promise<OffboardingDecisionResult> {
  return apiFetch<OffboardingDecisionResult>(
    `/hierarchy/offboarding/${encodeURIComponent(caseId)}/reject`,
    { method: 'POST', body: { note } },
  );
}
