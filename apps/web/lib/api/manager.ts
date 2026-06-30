import type {
  Approval,
  NotificationFeed,
  NotificationItem,
  RejectApprovalInput,
} from '@/lib/contract';
import { apiFetch } from './client';

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
