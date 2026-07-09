import type {
  MailParty,
  MailUnreadCount,
  ReplyMessageInput,
  SendMessageInput,
  SendMessageResult,
  ThreadDetail,
  ThreadPage,
} from '@/lib/contract';
import { apiFetch } from './client';

/**
 * Internal-mail API (ARCHITECTURE.md §8) — thread-based (Stage 3). Every call acts as the signed-in
 * staff user on their own mailbox; the send graph is enforced server-side (both new sends and replies).
 */

// --- Query keys (array convention, mail-namespaced) -----------------------

export const mailKeys = {
  unread: ['mail', 'unread-count'] as const,
  contacts: ['mail', 'contacts'] as const,
  inbox: (page: number) => ['mail', 'inbox', page] as const,
  sent: (page: number) => ['mail', 'sent', page] as const,
  search: (q: string, page: number) => ['mail', 'search', q, page] as const,
  thread: (id: string) => ['mail', 'thread', id] as const,
};

// --- Reads ----------------------------------------------------------------

/** The accounts the caller may message — exactly the send graph, as a concrete list. */
export function getMailContacts(signal?: AbortSignal): Promise<MailParty[]> {
  return apiFetch<MailParty[]>('/mail/contacts', { signal });
}

export function getUnreadCount(signal?: AbortSignal): Promise<MailUnreadCount> {
  return apiFetch<MailUnreadCount>('/mail/unread-count', { signal });
}

export function getInbox(page = 0, size = 20, signal?: AbortSignal): Promise<ThreadPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<ThreadPage>(`/mail/inbox?${params.toString()}`, { signal });
}

export function getSent(page = 0, size = 20, signal?: AbortSignal): Promise<ThreadPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<ThreadPage>(`/mail/sent?${params.toString()}`, { signal });
}

/** Search the caller's own mail (subject + body, case-insensitive). */
export function searchMail(q: string, page = 0, size = 20, signal?: AbortSignal): Promise<ThreadPage> {
  const params = new URLSearchParams({ q, page: String(page), size: String(size) });
  return apiFetch<ThreadPage>(`/mail/search?${params.toString()}`, { signal });
}

/** Open a thread (participant only). Server-side this stamps the viewer's unread messages read. */
export function getThread(id: string, signal?: AbortSignal): Promise<ThreadDetail> {
  return apiFetch<ThreadDetail>(`/mail/threads/${encodeURIComponent(id)}`, { signal });
}

// --- Writes ---------------------------------------------------------------

/** Start a new thread. 403 if the send graph forbids it. */
export function sendMessage(body: SendMessageInput): Promise<SendMessageResult> {
  return apiFetch<SendMessageResult>('/mail/messages', { method: 'POST', body });
}

/** Reply within a thread — recipient derived server-side, still graph-checked. */
export function replyToThread(threadId: string, body: ReplyMessageInput): Promise<SendMessageResult> {
  return apiFetch<SendMessageResult>(`/mail/threads/${encodeURIComponent(threadId)}/reply`, {
    method: 'POST',
    body,
  });
}

export function markThreadRead(id: string): Promise<MailUnreadCount> {
  return apiFetch<MailUnreadCount>(`/mail/threads/${encodeURIComponent(id)}/read`, { method: 'POST' });
}

export function markThreadUnread(id: string): Promise<MailUnreadCount> {
  return apiFetch<MailUnreadCount>(`/mail/threads/${encodeURIComponent(id)}/unread`, {
    method: 'POST',
  });
}

/** Delete a thread for the caller only (per-user soft-hide). */
export function deleteThread(id: string): Promise<void> {
  return apiFetch<void>(`/mail/threads/${encodeURIComponent(id)}`, { method: 'DELETE' });
}
