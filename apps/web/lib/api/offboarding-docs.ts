import type {
  ClearanceView,
  CompleteOffboardingDocResult,
  IssueLetterRequest,
  LetterIssuePanel,
  LetterPreview,
  LetterView,
  LettersView,
  MyOffboardingDocSummary,
  MyOffboardingDocView,
  OffboardingDocSummary,
  OffboardingDocType,
  RecordDocuments,
  RequestType,
} from '@/lib/contract';
import type { RequestUpload } from '@/lib/contract';
import { apiFetch } from './client';

/** Offboarding documents + clearance (§3.6 stage 2). */

// --- HR ---------------------------------------------------------------------

/** The record documents section — statuses + the send specs. */
export function getRecordDocuments(employeeId: string, signal?: AbortSignal): Promise<RecordDocuments> {
  return apiFetch<RecordDocuments>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/documents`,
    { signal },
  );
}

export function sendOffboardingDocuments(
  employeeId: string,
  documents: Array<{ type: OffboardingDocType; hrValues: Record<string, string> }>,
): Promise<{ documents: OffboardingDocSummary[] }> {
  return apiFetch<{ documents: OffboardingDocSummary[] }>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/documents/send`,
    { method: 'POST', body: { documents } },
  );
}

export function verifyOffboardingDocument(
  employeeId: string,
  type: OffboardingDocType,
): Promise<RecordDocuments> {
  return apiFetch<RecordDocuments>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/documents/${type}/verify`,
    { method: 'POST' },
  );
}

export function sendBackOffboardingDocument(
  employeeId: string,
  type: OffboardingDocType,
  note: string,
): Promise<RecordDocuments> {
  return apiFetch<RecordDocuments>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/documents/${type}/send-back`,
    { method: 'POST', body: { note } },
  );
}

export function getClearance(employeeId: string, signal?: AbortSignal): Promise<ClearanceView> {
  return apiFetch<ClearanceView>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/clearance`,
    { signal },
  );
}

export function saveClearance(
  employeeId: string,
  body: {
    items: Record<string, { value: string | null; remarks: string | null }>;
    finalItSignoff: string | null;
    finalStatus: 'APPROVED' | 'PENDING' | 'ON_HOLD';
  },
): Promise<ClearanceView> {
  return apiFetch<ClearanceView>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/clearance`,
    { method: 'PUT', body },
  );
}

// --- Employee (workspace) ---------------------------------------------------

export function getMyOffboardingDocuments(signal?: AbortSignal): Promise<MyOffboardingDocSummary[]> {
  return apiFetch<MyOffboardingDocSummary[]>('/me/offboarding', { signal });
}

export function getMyOffboardingDocument(
  type: OffboardingDocType,
  signal?: AbortSignal,
): Promise<MyOffboardingDocView> {
  return apiFetch<MyOffboardingDocView>(`/me/offboarding/${type}`, { signal });
}

export function completeOffboardingDocument(
  type: OffboardingDocType,
  body: { consentAccepted: true; fillValues: Record<string, string>; signatureDataUrl: string },
): Promise<CompleteOffboardingDocResult> {
  return apiFetch<CompleteOffboardingDocResult>(`/me/offboarding/${type}/complete`, {
    method: 'POST',
    body,
  });
}

// --- Letters (§3.6 stage 3) -------------------------------------------------

/** Employee: my offboarding letters + whether the request gate is open (all documents verified). */
export function getMyLetters(signal?: AbortSignal): Promise<LettersView> {
  return apiFetch<LettersView>('/me/offboarding/letters', { signal });
}

/** Employee: request a letter (gated on all documents verified; routed to the case HR). */
export function requestLetter(type: RequestType, note?: string): Promise<LetterView> {
  return apiFetch<LetterView>(`/me/offboarding/letters/${type}`, {
    method: 'POST',
    body: { note: note ?? null },
  });
}

/** HR / record viewers: the two letters' issue specs (prefilled fields, issued state) + the gate. */
export function getRecordLetters(employeeId: string, signal?: AbortSignal): Promise<LetterIssuePanel> {
  return apiFetch<LetterIssuePanel>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/letters`,
    { signal },
  );
}

/** HR: preview the substituted letter text before issuing (a dry run — not gated, not persisted). */
export function previewLetter(
  employeeId: string,
  type: RequestType,
  body: IssueLetterRequest,
): Promise<LetterPreview> {
  return apiFetch<LetterPreview>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/letters/${type}/preview`,
    { method: 'POST', body },
  );
}

/** HR: issue (generate) the letter PDF — gated on all documents verified; resolves an open request. */
export function issueLetter(
  employeeId: string,
  type: RequestType,
  body: IssueLetterRequest,
): Promise<LetterIssuePanel> {
  return apiFetch<LetterIssuePanel>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/letters/${type}/issue`,
    { method: 'POST', body },
  );
}

/** HR fulfil step 1 (upload fallback): presigned PUT for the letter file, then upload; returns draft id. */
export async function uploadLetterFile(
  employeeId: string,
  type: RequestType,
  file: File,
): Promise<string> {
  const presign = await apiFetch<RequestUpload>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/letters/${type}/begin-upload`,
    { method: 'POST', body: { fileName: file.name, contentType: file.type, sizeBytes: file.size } },
  );
  const res = await fetch(presign.uploadUrl, {
    method: presign.method ?? 'PUT',
    headers: presign.headers ?? undefined,
    body: file,
  });
  if (!res.ok) {
    throw new Error('Upload failed');
  }
  return presign.documentId;
}

/** HR fulfil step 2 (upload fallback): bind the uploaded file + mark RESOLVED (notifies the employee). */
export function resolveLetter(
  employeeId: string,
  type: RequestType,
  documentIds: string[],
): Promise<LetterIssuePanel> {
  return apiFetch<LetterIssuePanel>(
    `/employees/${encodeURIComponent(employeeId)}/offboarding/letters/${type}/resolve`,
    { method: 'POST', body: { documentIds } },
  );
}

/** HR: complete the offboarding — case COMPLETED + employee OFFBOARDED. */
export function completeOffboarding(employeeId: string, note?: string): Promise<unknown> {
  return apiFetch<unknown>(`/employees/${encodeURIComponent(employeeId)}/offboarding/complete`, {
    method: 'POST',
    body: { note: note ?? null },
  });
}
