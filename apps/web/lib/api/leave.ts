import type {
  LeaveMinePage,
  LeaveRequest,
  SubmitLeaveInput,
  TeamLeavePage,
  TeamLeaveRow,
} from '@/lib/contract';
import { apiFetch } from './client';

/**
 * Leave API (§8b). Employee endpoints act as the credentialed employee (403 otherwise); the
 * {@code /leave/team/*} endpoints are MANAGER-only and scoped to the requests routed to the acting
 * Manager. No leave balances in v1 — history only.
 */

export const leaveKeys = {
  mine: (page: number) => ['leave', 'me', page] as const,
  team: (status: string, from: string, to: string, page: number) =>
    ['leave', 'team', status, from, to, page] as const,
};

// --- Employee --------------------------------------------------------------

export function submitLeave(body: SubmitLeaveInput): Promise<LeaveRequest> {
  return apiFetch<LeaveRequest>('/leave', { method: 'POST', body });
}

export function getMyLeave(page = 0, size = 20, signal?: AbortSignal): Promise<LeaveMinePage> {
  const q = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<LeaveMinePage>(`/leave/me?${q.toString()}`, { signal });
}

/** Cancel your own PENDING request — 409 once it has been decided. */
export function cancelLeave(id: string): Promise<LeaveRequest> {
  return apiFetch<LeaveRequest>(`/leave/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
}

// --- Manager (team-scope) --------------------------------------------------

export function getTeamLeave(
  filters: { status?: string; from?: string; to?: string; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<TeamLeavePage> {
  const q = new URLSearchParams({
    page: String(filters.page ?? 0),
    size: String(filters.size ?? 20),
  });
  if (filters.status) q.set('status', filters.status);
  if (filters.from) q.set('from', filters.from);
  if (filters.to) q.set('to', filters.to);
  return apiFetch<TeamLeavePage>(`/leave/team?${q.toString()}`, { signal });
}

export function approveLeave(id: string): Promise<TeamLeaveRow> {
  return apiFetch<TeamLeaveRow>(`/leave/team/${encodeURIComponent(id)}/approve`, { method: 'POST' });
}

export function rejectLeave(id: string, note: string): Promise<TeamLeaveRow> {
  return apiFetch<TeamLeaveRow>(`/leave/team/${encodeURIComponent(id)}/reject`, {
    method: 'POST',
    body: { note },
  });
}
