import type {
  AccountantStatus,
  ApprovedEmployeePage,
  AuditLogPage,
  EmployeeRecord,
  MyTeamView,
  ProvisionAccountantInput,
  ProvisionAccountantResult,
  RevealedSensitive,
  ViewerCompanyRow,
  ViewerTeamRow,
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

/** Remove the current Accounts Admin so a replacement can be provisioned. */
export function removeAccountsAdmin(): Promise<AccountantStatus> {
  return apiFetch<AccountantStatus>('/provisioning/accounts-admin', { method: 'DELETE' });
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

// --- Team-wise browsing (§2) ----------------------------------------------

/** ACCOUNTS_ADMIN: the companies to drill into (with team + approved-employee counts). */
export function getViewerCompanies(signal?: AbortSignal): Promise<ViewerCompanyRow[]> {
  return apiFetch<ViewerCompanyRow[]>('/accountant/companies', { signal });
}

/** ACCOUNTS_ADMIN: a company's teams (HR/Manager names + approved-employee count). */
export function getCompanyTeams(companyId: string, signal?: AbortSignal): Promise<ViewerTeamRow[]> {
  return apiFetch<ViewerTeamRow[]>(`/accountant/companies/${encodeURIComponent(companyId)}/teams`, {
    signal,
  });
}

/** A team's APPROVED employees. ACCOUNTS_ADMIN: any team; ACCOUNTANT: only their own (else 404). */
export function getTeamEmployees(
  teamId: string,
  query: { search?: string; page?: number; size?: number } = {},
  signal?: AbortSignal,
): Promise<ApprovedEmployeePage> {
  const params = new URLSearchParams();
  if (query.search) params.set('search', query.search);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 20));
  return apiFetch<ApprovedEmployeePage>(
    `/accountant/teams/${encodeURIComponent(teamId)}/employees?${params.toString()}`,
    { signal },
  );
}

/** ACCOUNTANT: their own team descriptor (roster header); null if none is assigned. */
export function getMyTeam(signal?: AbortSignal): Promise<MyTeamView | null> {
  return apiFetch<MyTeamView | null>('/accountant/my-team', { signal });
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
