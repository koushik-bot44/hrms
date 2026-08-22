import type {
  CompaniesResponse,
  CompanyBreakdown,
  HierarchyStatus,
  PlatformOverview,
  ProvisionHierarchyInput,
  ProvisionHierarchyResult,
  TeamMembersResponse,
  TrendsResponse,
} from '@/lib/contract';
import { apiFetch } from './client';

// --- Provisioning (SUPER_ADMIN) -------------------------------------------

/** Whether the singleton Hierarchy exists — drives the Super Admin "Hierarchy" provisioning UI. */
export function getHierarchyStatus(signal?: AbortSignal): Promise<HierarchyStatus> {
  return apiFetch<HierarchyStatus>('/provisioning/hierarchy', { signal });
}

export function provisionHierarchy(body: ProvisionHierarchyInput): Promise<ProvisionHierarchyResult> {
  return apiFetch<ProvisionHierarchyResult>('/provisioning/hierarchy', { method: 'POST', body });
}

/** Remove the current Hierarchy so a replacement can be provisioned. */
export function removeHierarchy(): Promise<HierarchyStatus> {
  return apiFetch<HierarchyStatus>('/provisioning/hierarchy', { method: 'DELETE' });
}

// --- Platform overview reads (HIERARCHY; cross-company, aggregates-only) ----

/** Platform totals + onboarding funnel + status distribution + ops metrics, in one payload. */
export function getHierarchyOverview(signal?: AbortSignal): Promise<PlatformOverview> {
  return apiFetch<PlatformOverview>('/hierarchy/overview', { signal });
}

/** Monthly trends (last {@code months}, default 12; Asia/Kolkata): joined, approved, offboarded(=0). */
export function getHierarchyTrends(months: number, signal?: AbortSignal): Promise<TrendsResponse> {
  return apiFetch<TrendsResponse>(`/hierarchy/trends?months=${months}`, { signal });
}

/** Employees-per-company (size distribution + drill list): name, archived flag, team + employee counts. */
export function getHierarchyCompanies(signal?: AbortSignal): Promise<CompaniesResponse> {
  return apiFetch<CompaniesResponse>('/hierarchy/companies', { signal });
}

/** One company's org breakdown: counts + by-status + assigned staff (Company Admin, per-team HR/Mgr/Acc). */
export function getHierarchyBreakdown(
  companyId: string,
  signal?: AbortSignal,
): Promise<CompanyBreakdown> {
  return apiFetch<CompanyBreakdown>(
    `/hierarchy/companies/${encodeURIComponent(companyId)}/breakdown`,
    { signal },
  );
}

/**
 * A team's people (§2 charter widening): assigned staff + employees — full name, employee code, designation
 * and role ONLY (no other PII). Opaque ids in the URL (platform-level area; slug is never used here).
 */
export function getHierarchyTeamMembers(
  teamId: string,
  signal?: AbortSignal,
): Promise<TeamMembersResponse> {
  return apiFetch<TeamMembersResponse>(
    `/hierarchy/teams/${encodeURIComponent(teamId)}/members`,
    { signal },
  );
}
