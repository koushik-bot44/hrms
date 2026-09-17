import {
  ALLOWED_UPLOAD_MIME_TYPES,
  MAX_UPLOAD_BYTES,
  type AllowedUploadMimeType,
  type DecisionResult,
  type DocumentDto,
  type DocumentType,
  type Form1Values,
  type Form1View,
  type Form3EntryView,
  type Form3Values,
  type MyOfferView,
  type OnboardingDashboard,
  type PresignedUpload,
  type PresignedView,
  type SignatureValues,
  type SignatureView,
} from '@/lib/contract';
import { ApiError, apiFetch } from './client';

export function getDashboard(signal?: AbortSignal): Promise<OnboardingDashboard> {
  return apiFetch<OnboardingDashboard>('/me/onboarding', { signal });
}

// --- Offer Letter (§3.2): the gate that opens onboarding --------------------

/** The invited employee's offer (full text + status); the API returns null when there is no offer. */
export function getMyOffer(signal?: AbortSignal): Promise<MyOfferView | null> {
  return apiFetch<MyOfferView | null>('/me/onboarding/offer', { signal });
}

/** Accept the offer — consent + signature; unlocks the forms + returns the fresh dashboard. */
export function acceptOffer(body: {
  consentAccepted: true;
  signatureDataUrl: string;
}): Promise<OnboardingDashboard> {
  return apiFetch<OnboardingDashboard>('/me/onboarding/offer/accept', { method: 'POST', body });
}

export function saveForm1(body: Form1Values): Promise<Form1View> {
  return apiFetch<Form1View>('/me/onboarding/form1', { method: 'PUT', body });
}

// Form 2 is HR/SA-authored at onboard (§3.2) — the employee has no Form-2 save endpoint.

export function saveForm3(body: Form3Values): Promise<Form3EntryView[]> {
  return apiFetch<Form3EntryView[]>('/me/onboarding/form3', { method: 'PUT', body });
}

export function saveSignature(body: SignatureValues): Promise<SignatureView> {
  return apiFetch<SignatureView>('/me/onboarding/signature', { method: 'PUT', body });
}

export function getDocumentViewUrl(id: string): Promise<PresignedView> {
  return apiFetch<PresignedView>(`/me/onboarding/documents/${id}/url`);
}

export function getGeneratedViewUrl(id: string): Promise<PresignedView> {
  return apiFetch<PresignedView>(`/me/onboarding/generated/${id}/url`);
}

export function deleteDocument(id: string): Promise<OnboardingDashboard> {
  return apiFetch<OnboardingDashboard>(`/me/onboarding/documents/${id}`, { method: 'DELETE' });
}

export function submitOnboarding(): Promise<OnboardingDashboard> {
  return apiFetch<OnboardingDashboard>('/me/onboarding/submit', { method: 'POST' });
}

/** Re-submit after fixing the items HR sent back for revision (§3.3). */
export function resubmitOnboarding(): Promise<OnboardingDashboard> {
  return apiFetch<OnboardingDashboard>('/me/onboarding/resubmit', { method: 'POST' });
}

function isAllowedMime(type: string): type is AllowedUploadMimeType {
  return (ALLOWED_UPLOAD_MIME_TYPES as readonly string[]).includes(type);
}

/**
 * Full Form 4 upload: request a presigned PUT, upload the file directly to storage (with progress),
 * then confirm so the server hashes it. {@code groupIndex} (1..4) is required for the per-employment
 * slots. Validates type/size first.
 */
export function uploadDocument(
  file: File,
  docType: DocumentType,
  groupIndex: number | null,
  onProgress?: (percent: number) => void,
): Promise<DocumentDto> {
  return uploadDocumentTo('/me/onboarding', file, docType, groupIndex, onProgress);
}

/** The Form 4 upload against an onboarding base path (`/me/onboarding` or HR's `/employees/{id}/onboarding`). */
async function uploadDocumentTo(
  base: string,
  file: File,
  docType: DocumentType,
  groupIndex: number | null,
  onProgress?: (percent: number) => void,
): Promise<DocumentDto> {
  if (!isAllowedMime(file.type)) {
    throw new ApiError(400, 'Unsupported file type — use PDF, PNG, or JPEG');
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    throw new ApiError(400, 'File is too large (max 10 MB)');
  }

  const presign = await apiFetch<PresignedUpload>(`${base}/documents`, {
    method: 'POST',
    body: {
      docType,
      ...(groupIndex != null ? { groupIndex } : {}),
      fileName: file.name,
      mimeType: file.type,
      sizeBytes: file.size,
    },
  });

  await putWithProgress(presign.uploadUrl, file, presign.headers, onProgress);

  return apiFetch<DocumentDto>(`${base}/documents/${presign.documentId}/confirm`, {
    method: 'POST',
  });
}

/**
 * The write surface an onboarding form step targets (§3.2): the signed-in employee's own record
 * ({@link SELF_ONBOARDING}), or an EXISTING employee's record that HR fills in ({@link employeeOnboardingTarget}).
 * `queryKey` is the dashboard query each target refetches after a write.
 */
export interface OnboardingTarget {
  queryKey: readonly unknown[];
  saveForm1: (body: Form1Values) => Promise<Form1View>;
  saveForm3: (body: Form3Values) => Promise<Form3EntryView[]>;
  saveSignature: (body: SignatureValues) => Promise<SignatureView>;
  uploadDocument: (
    file: File,
    docType: DocumentType,
    groupIndex: number | null,
    onProgress?: (percent: number) => void,
  ) => Promise<DocumentDto>;
  deleteDocument: (id: string) => Promise<OnboardingDashboard>;
  getDocumentViewUrl: (id: string) => Promise<PresignedView>;
}

/** The employee filling their own onboarding (`/me/onboarding`). */
export const SELF_ONBOARDING: OnboardingTarget = {
  queryKey: ['onboarding'],
  saveForm1,
  saveForm3,
  saveSignature,
  uploadDocument,
  deleteDocument,
  getDocumentViewUrl,
};

// --- HR entry for an EXISTING employee (§3.2) --------------------------------
// An existing employee never signs in to onboard: HR enters their details, uploads their documents and a scanned
// signature, then approves (no separate verify step). HR-only, own onboarded, until approved.

const hrBase = (employeeId: string) => `/employees/${encodeURIComponent(employeeId)}/onboarding`;

export const employeeOnboardingKey = (employeeId: string) => ['hr-onboarding', employeeId] as const;

/** The existing employee's onboarding data for HR entry — the same shape the employee's own dashboard uses. */
export function getEmployeeOnboarding(employeeId: string, signal?: AbortSignal): Promise<OnboardingDashboard> {
  return apiFetch<OnboardingDashboard>(hrBase(employeeId), { signal });
}

/** HR's write surface for an existing employee's record. */
export function employeeOnboardingTarget(employeeId: string): OnboardingTarget {
  const base = hrBase(employeeId);
  return {
    queryKey: employeeOnboardingKey(employeeId),
    saveForm1: (body) => apiFetch<Form1View>(`${base}/form1`, { method: 'PUT', body }),
    saveForm3: (body) => apiFetch<Form3EntryView[]>(`${base}/form3`, { method: 'PUT', body }),
    saveSignature: (body) => apiFetch<SignatureView>(`${base}/signature`, { method: 'PUT', body }),
    uploadDocument: (file, docType, groupIndex, onProgress) =>
      uploadDocumentTo(base, file, docType, groupIndex, onProgress),
    deleteDocument: (id) =>
      apiFetch<OnboardingDashboard>(`${base}/documents/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    getDocumentViewUrl: (id) =>
      apiFetch<PresignedView>(`${base}/documents/${encodeURIComponent(id)}/url`),
  };
}

/**
 * HR approves the existing employee once their record is complete. It KEEPS the employee ID HR entered on Form 2
 * (nothing is minted) and returns it as DecisionResult.employeeCode. No verify step — HR entered the data. The API
 * re-checks completeness (409 with what's missing). No email is sent.
 */
export function approveExistingEmployee(employeeId: string): Promise<DecisionResult> {
  return apiFetch<DecisionResult>(`${hrBase(employeeId)}/approve`, { method: 'POST' });
}

/**
 * Re-upload a document HR sent back for revision (§3.3): a fresh presigned PUT keyed to the SAME
 * document, replacing the file in place, then confirm (which returns it to UPLOADED for re-review).
 */
export async function reviseDocument(
  documentId: string,
  file: File,
  onProgress?: (percent: number) => void,
): Promise<DocumentDto> {
  if (!isAllowedMime(file.type)) {
    throw new ApiError(400, 'Unsupported file type — use PDF, PNG, or JPEG');
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    throw new ApiError(400, 'File is too large (max 10 MB)');
  }

  const presign = await apiFetch<PresignedUpload>(
    `/me/onboarding/documents/${documentId}/revise`,
    { method: 'POST', body: { fileName: file.name, mimeType: file.type, sizeBytes: file.size } },
  );

  await putWithProgress(presign.uploadUrl, file, presign.headers, onProgress);

  return apiFetch<DocumentDto>(`/me/onboarding/documents/${presign.documentId}/confirm`, {
    method: 'POST',
  });
}

/** Direct PUT to object storage with upload progress (fetch lacks upload progress). Shared with mail. */
export function putWithProgress(
  url: string,
  file: File,
  headers: Record<string, string>,
  onProgress?: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url);
    for (const [key, value] of Object.entries(headers)) {
      xhr.setRequestHeader(key, value);
    }
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        resolve();
      } else {
        reject(new ApiError(xhr.status, `Upload failed (${xhr.status})`));
      }
    };
    xhr.onerror = () => reject(new ApiError(0, 'Upload failed — check your connection'));
    xhr.send(file);
  });
}
