import type {
  AssignMemberInput,
  AssignMemberResult,
  AssignableUser,
  CreateTeamInput,
  TeamDetail,
  TeamRole,
  TeamSummary,
  UpdateTeamInput,
} from '@/lib/contract';
import { apiFetch } from './client';

/**
 * Team management API. COMPANY_ADMIN hits `/teams` (own company). SUPER_ADMIN passes a `companyId` to
 * hit `/companies/{companyId}/teams` for ANY company — the same operations, the same components. The
 * query-key helpers namespace SUPER_ADMIN's per-company data so the two never collide in the cache.
 */
function base(companyId?: string): string {
  return companyId ? `/companies/${companyId}/teams` : '/teams';
}

export const teamsKey = (companyId?: string): string[] =>
  companyId ? ['company-teams', companyId] : ['teams'];
export const teamKey = (teamId: string, companyId?: string): string[] =>
  companyId ? ['company-team', companyId, teamId] : ['team', teamId];
export const assignableKey = (role: TeamRole, companyId?: string): string[] =>
  companyId ? ['company-assignable', companyId, role] : ['assignable', role];

export function listTeams(companyId?: string, signal?: AbortSignal): Promise<TeamSummary[]> {
  return apiFetch<TeamSummary[]>(base(companyId), { signal });
}

export function getTeam(id: string, companyId?: string, signal?: AbortSignal): Promise<TeamDetail> {
  return apiFetch<TeamDetail>(`${base(companyId)}/${id}`, { signal });
}

export function createTeam(body: CreateTeamInput, companyId?: string): Promise<TeamDetail> {
  return apiFetch<TeamDetail>(base(companyId), { method: 'POST', body });
}

export function updateTeam(
  id: string,
  body: UpdateTeamInput,
  companyId?: string,
): Promise<TeamDetail> {
  return apiFetch<TeamDetail>(`${base(companyId)}/${id}`, { method: 'PATCH', body });
}

export function deleteTeam(id: string, companyId?: string): Promise<unknown> {
  return apiFetch(`${base(companyId)}/${id}`, { method: 'DELETE' });
}

export function getAssignableUsers(
  role: TeamRole,
  companyId?: string,
  signal?: AbortSignal,
): Promise<AssignableUser[]> {
  return apiFetch<AssignableUser[]>(`${base(companyId)}/assignable-users?role=${role}`, { signal });
}

export function assignTeamMember(
  id: string,
  role: TeamRole,
  body: AssignMemberInput,
  companyId?: string,
): Promise<AssignMemberResult> {
  const slot = role === 'HR' ? 'hr' : 'manager';
  return apiFetch<AssignMemberResult>(`${base(companyId)}/${id}/${slot}`, { method: 'PUT', body });
}
