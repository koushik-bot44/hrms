import type {
  ApproveInput,
  AssignCredentialsInput,
  AssignCredentialsResult,
  DecisionResult,
  EmployeeRecord,
  PresignedView,
  RejectInput,
  RevealedSensitive,
  ReviewInput,
} from '@/lib/contract';
import { apiFetch } from './client';

/** Open an employee's record by INTERNAL id — the verification entry (pre-approval has no code). */
export function getEmployeeRecord(id: string, signal?: AbortSignal): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/employees/${encodeURIComponent(id)}/record`, { signal });
}

/**
 * A short-lived presigned URL to the accepted Offer Letter PDF (§3.2) — role-gated (HR/COMPANY_ADMIN/
 * SUPER_ADMIN) because it carries the salary; manager/accountant get a 403. 404 until the offer is accepted.
 */
export function getOfferPdfUrl(id: string): Promise<PresignedView> {
  return apiFetch<PresignedView>(`/employees/${encodeURIComponent(id)}/offer/pdf`);
}

/**
 * HR deactivates an offboarded employee's account (§3.6) — disables BOTH sign-in doors. One-way; 409 if
 * already deactivated. Allowed only once the employee is OFFBOARDED (the record's Offboarding panel gates it).
 */
export function deactivateAccount(id: string): Promise<void> {
  return apiFetch<void>(`/employees/${encodeURIComponent(id)}/deactivate`, { method: 'POST' });
}

/** §3.4 records lookup by employee ID (code) — resolves approved employees only. */
export function lookupEmployeeByCode(
  employeeCode: string,
  signal?: AbortSignal,
): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/employees/lookup/${encodeURIComponent(employeeCode)}`, { signal });
}

/** Reveal the plaintext sensitive values — an explicit, audited action (§6). */
export function revealSensitive(id: string): Promise<RevealedSensitive> {
  return apiFetch<RevealedSensitive>(`/employees/${encodeURIComponent(id)}/reveal`, {
    method: 'POST',
  });
}

/** Verify or reject a whole form (FORM1 / FORM2 / FORM3). */
export function reviewForm(id: string, form: string, body: ReviewInput): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(
    `/employees/${encodeURIComponent(id)}/forms/${encodeURIComponent(form)}`,
    { method: 'PATCH', body },
  );
}

export function reviewDocument(
  id: string,
  documentId: string,
  body: ReviewInput,
): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(
    `/employees/${encodeURIComponent(id)}/documents/${encodeURIComponent(documentId)}`,
    { method: 'PATCH', body },
  );
}

/** HR APPROVES a verified employee (§3.3) — mints the ID; the server resolves the team (onboarding-HR's). */
export function approveEmployee(id: string, body: ApproveInput): Promise<DecisionResult> {
  return apiFetch<DecisionResult>(`/employees/${encodeURIComponent(id)}/approve`, {
    method: 'POST',
    body,
  });
}

/** HR terminally REJECTS a verified application (§3.3). */
export function rejectEmployee(id: string, body: RejectInput): Promise<DecisionResult> {
  return apiFetch<DecisionResult>(`/employees/${encodeURIComponent(id)}/reject`, {
    method: 'POST',
    body,
  });
}

/** Assign (or re-issue) an APPROVED employee internal credentials (§8, Stage 5). HR-only. */
export function assignEmployeeCredentials(
  id: string,
  body: AssignCredentialsInput,
): Promise<AssignCredentialsResult> {
  return apiFetch<AssignCredentialsResult>(`/employees/${encodeURIComponent(id)}/credentials`, {
    method: 'POST',
    body,
  });
}
