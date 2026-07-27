import type {
  MailAttachmentUpload,
  MailParty,
  MailUnreadCount,
  ReplyMessageInput,
  SendMessageInput,
  SendMessageResult,
  ThreadDetail,
  ThreadPage,
} from '@/lib/contract';
import { validateMailAttachment } from '@/lib/contract';
import { apiFetch, ApiError } from './client';
import { putWithProgress } from './onboarding';

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
  starred: (page: number) => ['mail', 'starred', page] as const,
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

/** The caller's starred conversations (per-user), same shape + soft-delete scoping as Inbox. */
export function getStarred(page = 0, size = 20, signal?: AbortSignal): Promise<ThreadPage> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<ThreadPage>(`/mail/starred?${params.toString()}`, { signal });
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

/** Reply All — to the original sender + TO + CC (never BCC); each re-validated server-side. */
export function replyAllToThread(threadId: string, body: ReplyMessageInput): Promise<SendMessageResult> {
  return apiFetch<SendMessageResult>(`/mail/threads/${encodeURIComponent(threadId)}/reply-all`, {
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

/** Star a thread for the caller only (per-user, thread-level; idempotent). */
export function starThread(id: string): Promise<void> {
  return apiFetch<void>(`/mail/threads/${encodeURIComponent(id)}/star`, { method: 'POST' });
}

/** Unstar a thread for the caller only (per-user; idempotent). */
export function unstarThread(id: string): Promise<void> {
  return apiFetch<void>(`/mail/threads/${encodeURIComponent(id)}/star`, { method: 'DELETE' });
}

// --- Attachments (§8, Stage 4) --------------------------------------------

/**
 * Upload one attachment via the presigned handshake: validate (server re-validates), request a presigned
 * PUT, upload the bytes directly to storage, and return the DRAFT attachment id to send with the message.
 */
export async function uploadMailAttachment(
  file: File,
  onProgress?: (percent: number) => void,
): Promise<string> {
  const error = validateMailAttachment(file);
  if (error) {
    throw new ApiError(400, error);
  }
  const presign = await apiFetch<MailAttachmentUpload>('/mail/attachments/upload-url', {
    method: 'POST',
    body: {
      fileName: file.name,
      contentType: file.type,
      sizeBytes: file.size,
    },
  });
  await putWithProgress(presign.uploadUrl, file, presign.headers, onProgress);
  return presign.attachmentId;
}

/** Resolve a short-lived, participant-scoped presigned download URL for an attachment. */
export function getAttachmentDownloadUrl(attachmentId: string): Promise<{ url: string }> {
  return apiFetch<{ url: string }>(
    `/mail/attachments/${encodeURIComponent(attachmentId)}/download`,
  );
}
