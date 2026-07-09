import type {
  InboxPage,
  MailMessage,
  MailParty,
  MailUnreadCount,
  SendMessageInput,
  SendMessageResult,
  SentPage,
} from '@/lib/contract';
import { apiFetch } from './client';

/**
 * Internal-mail API (ARCHITECTURE.md §8). Every call acts as the signed-in staff user on their own
 * mailbox; the send graph is enforced server-side (the recipient list here is already graph-derived).
 */

// --- Query keys (array convention, mail-namespaced) -----------------------

export const mailKeys = {
  unread: ['mail', 'unread-count'] as const,
  contacts: ['mail', 'contacts'] as const,
  inbox: (page: number) => ['mail', 'inbox', page] as const,
  sent: (page: number) => ['mail', 'sent', page] as const,
  message: (id: string) => ['mail', 'message', id] as const,
};

// --- Reads ----------------------------------------------------------------

/** The accounts the caller may message — exactly the send graph, as a concrete list. */
export function getMailContacts(signal?: AbortSignal): Promise<MailParty[]> {
  return apiFetch<MailParty[]>('/mail/contacts', { signal });
}

export function getUnreadCount(signal?: AbortSignal): Promise<MailUnreadCount> {
  return apiFetch<MailUnreadCount>('/mail/unread-count', { signal });
}

export function getInbox(page = 0, size = 20, signal?: AbortSignal): Promise<InboxPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<InboxPage>(`/mail/inbox?${params.toString()}`, { signal });
}

export function getSent(page = 0, size = 20, signal?: AbortSignal): Promise<SentPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<SentPage>(`/mail/sent?${params.toString()}`, { signal });
}

/** Open one message (sender or recipient only). Server-side this stamps the recipient's read_at. */
export function getMessage(id: string, signal?: AbortSignal): Promise<MailMessage> {
  return apiFetch<MailMessage>(`/mail/messages/${encodeURIComponent(id)}`, { signal });
}

// --- Write ----------------------------------------------------------------

export function sendMessage(body: SendMessageInput): Promise<SendMessageResult> {
  return apiFetch<SendMessageResult>('/mail/messages', { method: 'POST', body });
}
