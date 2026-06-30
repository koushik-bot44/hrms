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

export function listTeams(signal?: AbortSignal): Promise<TeamSummary[]> {
  return apiFetch<TeamSummary[]>('/teams', { signal });
}

export function getTeam(id: string, signal?: AbortSignal): Promise<TeamDetail> {
  return apiFetch<TeamDetail>(`/teams/${id}`, { signal });
}

export function createTeam(body: CreateTeamInput): Promise<TeamDetail> {
  return apiFetch<TeamDetail>('/teams', { method: 'POST', body });
}

export function updateTeam(id: string, body: UpdateTeamInput): Promise<TeamDetail> {
  return apiFetch<TeamDetail>(`/teams/${id}`, { method: 'PATCH', body });
}

export function deleteTeam(id: string): Promise<unknown> {
  return apiFetch(`/teams/${id}`, { method: 'DELETE' });
}

export function getAssignableUsers(role: TeamRole, signal?: AbortSignal): Promise<AssignableUser[]> {
  return apiFetch<AssignableUser[]>(`/teams/assignable-users?role=${role}`, { signal });
}

export function assignTeamMember(
  id: string,
  role: TeamRole,
  body: AssignMemberInput,
): Promise<AssignMemberResult> {
  const slot = role === 'HR' ? 'hr' : 'manager';
  return apiFetch<AssignMemberResult>(`/teams/${id}/${slot}`, { method: 'PUT', body });
}
