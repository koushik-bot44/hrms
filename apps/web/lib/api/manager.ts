import type {
  Approval,
  EmployeeRecord,
  MyTeamView,
  NotificationFeed,
  NotificationItem,
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

// --- Team onboarding (read-only) ------------------------------------------

/**
 * The Manager's READ-ONLY team-onboarding history — the employees decided onto their team (approved, plus
 * any legacy manager-era rejects), most recently decided first. Approval authority now sits with HR (§3.3).
 */
export function getApprovalHistory(signal?: AbortSignal): Promise<Approval[]> {
  return apiFetch<Approval[]>('/manager/approvals/history', { signal });
}

/** The employee record (forms + documents with view URLs) behind one of the team's onboarding decisions. */
export function getApprovalRecord(id: string, signal?: AbortSignal): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/manager/approvals/${encodeURIComponent(id)}/record`, { signal });
}
