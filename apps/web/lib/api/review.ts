import type {
  EmployeeRecord,
  RevealedSensitive,
  ReviewInput,
  RouteToManagerInput,
  RouteToManagerResult,
} from '@/lib/contract';
import { apiFetch } from './client';

/** Open an employee's record by INTERNAL id — the verification entry (pre-approval has no code). */
export function getEmployeeRecord(id: string, signal?: AbortSignal): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/employees/${encodeURIComponent(id)}/record`, { signal });
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

export function routeToManager(id: string, body: RouteToManagerInput): Promise<RouteToManagerResult> {
  return apiFetch<RouteToManagerResult>(
    `/employees/${encodeURIComponent(id)}/route-to-manager`,
    { method: 'POST', body },
  );
}
