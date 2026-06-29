import {
  AssignMemberResultSchema,
  AssignableUserListSchema,
  TeamDetailSchema,
  TeamListSchema,
  type AssignMemberInput,
  type AssignMemberResult,
  type AssignableUser,
  type CreateTeamInput,
  type TeamDetail,
  type TeamRole,
  type TeamSummary,
  type UpdateTeamInput,
} from '@ihrms/shared';
import { apiFetch } from './client';

export function listTeams(signal?: AbortSignal): Promise<TeamSummary[]> {
  return apiFetch('/teams', { schema: TeamListSchema, signal });
}

export function getTeam(id: string, signal?: AbortSignal): Promise<TeamDetail> {
  return apiFetch(`/teams/${id}`, { schema: TeamDetailSchema, signal });
}

export function createTeam(body: CreateTeamInput): Promise<TeamDetail> {
  return apiFetch('/teams', { method: 'POST', body, schema: TeamDetailSchema });
}

export function updateTeam(id: string, body: UpdateTeamInput): Promise<TeamDetail> {
  return apiFetch(`/teams/${id}`, { method: 'PATCH', body, schema: TeamDetailSchema });
}

export function deleteTeam(id: string): Promise<unknown> {
  return apiFetch(`/teams/${id}`, { method: 'DELETE' });
}

export function getAssignableUsers(role: TeamRole, signal?: AbortSignal): Promise<AssignableUser[]> {
  return apiFetch(`/teams/assignable-users?role=${role}`, {
    schema: AssignableUserListSchema,
    signal,
  });
}

export function assignTeamMember(
  id: string,
  role: TeamRole,
  body: AssignMemberInput,
): Promise<AssignMemberResult> {
  const slot = role === 'HR' ? 'hr' : 'manager';
  return apiFetch(`/teams/${id}/${slot}`, { method: 'PUT', body, schema: AssignMemberResultSchema });
}
