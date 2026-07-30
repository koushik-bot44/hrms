import type {
  Approval,
  EmployeeRecord,
  MyTeamView,
  NotificationFeed,
  NotificationItem,
  RejectApprovalInput,
} from '@/lib/contract';
import { apiFetch } from './client';

// --- Team descriptor ------------------------------------------------------

/**
 * The Manager's own team descriptor (§8a) — resolves the {@code teamId} the attendance-analytics tab hands
 * to the SHARED viewer components. Mirrors the Accountant's `/accountant/my-team`; null if unassigned.
 */
export function getMyManagerTeam(signal?: AbortSignal): Promise<MyTeamView | null> {
  return apiFetch<MyTeamView | null>('/manager/my-team', { signal });
}

// --- Notifications --------------------------------------------------------

export function getNotifications(signal?: AbortSignal): Promise<NotificationFeed> {
  return apiFetch<NotificationFeed>('/manager/notifications', { signal });
}

export function markNotificationRead(id: string): Promise<NotificationItem> {
  return apiFetch<NotificationItem>(`/manager/notifications/${encodeURIComponent(id)}/read`, {
    method: 'POST',
  });
}

export function markAllNotificationsRead(): Promise<NotificationFeed> {
  return apiFetch<NotificationFeed>('/manager/notifications/read-all', { method: 'POST' });
}

// --- Approvals ------------------------------------------------------------

export function getApprovals(signal?: AbortSignal): Promise<Approval[]> {
  return apiFetch<Approval[]>('/manager/approvals', { signal });
}

/** Past decisions (approved + rejected), most recently decided first. */
export function getApprovalHistory(signal?: AbortSignal): Promise<Approval[]> {
  return apiFetch<Approval[]>('/manager/approvals/history', { signal });
}

/** The employee record (sections + documents with view URLs) behind one of the manager's approvals. */
export function getApprovalRecord(id: string, signal?: AbortSignal): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/manager/approvals/${encodeURIComponent(id)}/record`, { signal });
}

export function approveApproval(id: string): Promise<Approval> {
  return apiFetch<Approval>(`/manager/approvals/${encodeURIComponent(id)}/approve`, {
    method: 'POST',
  });
}

export function rejectApproval(id: string, body: RejectApprovalInput): Promise<Approval> {
  return apiFetch<Approval>(`/manager/approvals/${encodeURIComponent(id)}/reject`, {
    method: 'POST',
    body,
  });
}
