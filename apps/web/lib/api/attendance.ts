import type {
  ClockStatus,
  MyAttendancePage,
  TeamActivityPage,
  TeamAttendanceRow,
} from '@/lib/contract';
import { apiFetch } from './client';

/**
 * Attendance API (§8a). Employee endpoints act as the credentialed employee (403 otherwise); the
 * {@code /team/*} endpoints are MANAGER-only and team-scoped server-side. All times are UTC ISO strings
 * that the UI renders in Asia/Kolkata; durations/totals come computed from the server.
 */

export const attendanceKeys = {
  status: ['attendance', 'status'] as const,
  mine: (from: string, to: string, page: number) => ['attendance', 'me', from, to, page] as const,
  teamSummary: (from: string, to: string) => ['attendance', 'team-summary', from, to] as const,
  teamEmployee: (employeeId: string, from: string, to: string, page: number) =>
    ['attendance', 'team-employee', employeeId, from, to, page] as const,
  teamActivity: (page: number) => ['attendance', 'team-activity', page] as const,
};

// --- Employee --------------------------------------------------------------

export function getClockStatus(signal?: AbortSignal): Promise<ClockStatus> {
  return apiFetch<ClockStatus>('/attendance/me/status', { signal });
}

/** Clock in — rejects with a 409 if already clocked in. */
export function clockIn(): Promise<ClockStatus> {
  return apiFetch<ClockStatus>('/attendance/clock-in', { method: 'POST' });
}

/** Clock out — rejects with a 409 if not clocked in. */
export function clockOut(): Promise<ClockStatus> {
  return apiFetch<ClockStatus>('/attendance/clock-out', { method: 'POST' });
}

export function getMyAttendance(
  from: string,
  to: string,
  page = 0,
  size = 50,
  signal?: AbortSignal,
): Promise<MyAttendancePage> {
  const q = rangeQuery(from, to, page, size);
  return apiFetch<MyAttendancePage>(`/attendance/me?${q}`, { signal });
}

// --- Manager (team-scope) --------------------------------------------------

export function getTeamSummary(from: string, to: string, signal?: AbortSignal): Promise<TeamAttendanceRow[]> {
  const q = new URLSearchParams({ from, to });
  return apiFetch<TeamAttendanceRow[]>(`/attendance/team/summary?${q.toString()}`, { signal });
}

export function getTeamEmployeeAttendance(
  employeeId: string,
  from: string,
  to: string,
  page = 0,
  size = 50,
  signal?: AbortSignal,
): Promise<MyAttendancePage> {
  const q = rangeQuery(from, to, page, size);
  q.set('employeeId', employeeId);
  return apiFetch<MyAttendancePage>(`/attendance/team?${q.toString()}`, { signal });
}

export function getTeamActivity(page = 0, size = 30, signal?: AbortSignal): Promise<TeamActivityPage> {
  const q = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<TeamActivityPage>(`/attendance/team/activity?${q.toString()}`, { signal });
}

function rangeQuery(from: string, to: string, page: number, size: number): URLSearchParams {
  const q = new URLSearchParams({ page: String(page), size: String(size) });
  if (from) q.set('from', from);
  if (to) q.set('to', to);
  return q;
}
