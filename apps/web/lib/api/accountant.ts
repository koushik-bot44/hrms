import type {
  AccountantStatus,
  ApprovedEmployeePage,
  AuditLogPage,
  EmployeeRecord,
  ProvisionAccountantInput,
  ProvisionAccountantResult,
  RevealedSensitive,
} from '@/lib/contract';
import { apiFetch } from './client';

// --- Provisioning (SUPER_ADMIN) -------------------------------------------

/** Whether the singleton Accountant exists — drives the Super Admin "Create Accountant" UI. */
export function getAccountantStatus(signal?: AbortSignal): Promise<AccountantStatus> {
  return apiFetch<AccountantStatus>('/provisioning/accounts-admin', { signal });
}

export function provisionAccountant(body: ProvisionAccountantInput): Promise<ProvisionAccountantResult> {
  return apiFetch<ProvisionAccountantResult>('/provisioning/accounts-admin', { method: 'POST', body });
}

// --- Accountant reads (ACCOUNTANT) ----------------------------------------

export interface ApprovedQuery {
  search?: string;
  companyId?: string;
  page?: number;
  size?: number;
}

/** APPROVED employees across ALL companies, optional company filter + name/email/ID search. */
export function getApprovedEmployees(
  query: ApprovedQuery = {},
  signal?: AbortSignal,
): Promise<ApprovedEmployeePage> {
  const params = new URLSearchParams();
  if (query.search) params.set('search', query.search);
  if (query.companyId) params.set('companyId', query.companyId);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return apiFetch<ApprovedEmployeePage>(`/accountant/employees?${params.toString()}`, { signal });
}

/** An approved employee's full record (masked); the read is audited. */
export function getAccountantRecord(id: string, signal?: AbortSignal): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/accountant/employees/${encodeURIComponent(id)}`, { signal });
}

/** Reveal the masked sensitive values — an explicit, audited action (§6). */
export function revealAccountantSensitive(id: string): Promise<RevealedSensitive> {
  return apiFetch<RevealedSensitive>(`/accountant/employees/${encodeURIComponent(id)}/reveal`, {
    method: 'POST',
  });
}

/** Approval-only audit trail across all companies (optionally narrowed to one). */
export function getApprovalAudit(
  query: { companyId?: string; page?: number; size?: number } = {},
  signal?: AbortSignal,
): Promise<AuditLogPage> {
  const params = new URLSearchParams();
  if (query.companyId) params.set('companyId', query.companyId);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 25));
  return apiFetch<AuditLogPage>(`/accountant/audit?${params.toString()}`, { signal });
}
