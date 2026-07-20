import type {
  DocumentRequestView,
  MyRequestsPage,
  PresignedView,
  RequestUpload,
  SubmitRequestInput,
  TeamRequestRow,
  TeamRequestsPage,
} from '@/lib/contract';
import { validateMailAttachment } from '@/lib/contract';
import { ApiError, apiFetch } from './client';
import { putWithProgress } from './onboarding';

/**
 * HR/Accounts Requests API — Accounts side (§8d). Employee endpoints act as the credentialed employee
 * (403 otherwise); the {@code /requests/team/*} endpoints are ACCOUNTANT-only and scoped to the requests
 * routed to the acting Accountant. Fulfilment files reuse the presigned upload handshake (resolve binds).
 */

export const requestKeys = {
  mine: (page: number) => ['requests', 'me', page] as const,
  team: (status: string, page: number) => ['requests', 'team', status, page] as const,
};

// --- Employee --------------------------------------------------------------

export function submitRequest(body: SubmitRequestInput): Promise<DocumentRequestView> {
  return apiFetch<DocumentRequestView>('/requests', { method: 'POST', body });
}

export function getMyRequests(page = 0, size = 20, signal?: AbortSignal): Promise<MyRequestsPage> {
  const q = new URLSearchParams({ page: String(page), size: String(size) });
  return apiFetch<MyRequestsPage>(`/requests/me?${q.toString()}`, { signal });
}

/** Cancel your own SUBMITTED request — 409 once it is in progress / resolved. */
export function cancelRequest(id: string): Promise<DocumentRequestView> {
  return apiFetch<DocumentRequestView>(`/requests/${encodeURIComponent(id)}/cancel`, {
    method: 'POST',
  });
}

// --- Fulfilment document download (own employee OR routed accountant) ------

export function getRequestDocumentUrl(requestId: string, docId: string): Promise<PresignedView> {
  return apiFetch<PresignedView>(
    `/requests/${encodeURIComponent(requestId)}/documents/${encodeURIComponent(docId)}/download`,
  );
}

/** Open a resolved document in a new tab via its short-lived, audited presigned GET. */
export async function openRequestDocument(requestId: string, docId: string): Promise<void> {
  const { url } = await getRequestDocumentUrl(requestId, docId);
  window.open(url, '_blank', 'noopener');
}

// --- Accountant (team-scope) -----------------------------------------------

export function getTeamRequests(
  filters: { status?: string; page?: number; size?: number },
  signal?: AbortSignal,
): Promise<TeamRequestsPage> {
  const q = new URLSearchParams({
    page: String(filters.page ?? 0),
    size: String(filters.size ?? 20),
  });
  if (filters.status) q.set('status', filters.status);
  return apiFetch<TeamRequestsPage>(`/requests/team?${q.toString()}`, { signal });
}

export function pickUpRequest(id: string): Promise<TeamRequestRow> {
  return apiFetch<TeamRequestRow>(`/requests/team/${encodeURIComponent(id)}/pick-up`, {
    method: 'POST',
  });
}

/**
 * Upload one fulfilment file: request a presigned PUT (server validates type/extension/size), upload the
 * bytes directly to storage (with progress), and return the DRAFT document id to bind on resolve. The
 * file is validated client-side first (the server re-validates on both request and resolve).
 */
export async function uploadRequestDocument(
  requestId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<string> {
  const error = validateMailAttachment(file);
  if (error) {
    throw new ApiError(400, error);
  }
  const presign = await apiFetch<RequestUpload>(
    `/requests/team/${encodeURIComponent(requestId)}/documents/upload-url`,
    {
      method: 'POST',
      body: { fileName: file.name, contentType: file.type, sizeBytes: file.size },
    },
  );
  await putWithProgress(presign.uploadUrl, file, presign.headers, onProgress);
  return presign.documentId;
}

/** Bind the uploaded file(s) to the request and mark it RESOLVED. */
export function resolveRequest(
  id: string,
  body: { documentIds: string[]; note?: string },
): Promise<TeamRequestRow> {
  return apiFetch<TeamRequestRow>(`/requests/team/${encodeURIComponent(id)}/resolve`, {
    method: 'POST',
    body,
  });
}
