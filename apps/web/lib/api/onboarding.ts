import {
  ALLOWED_UPLOAD_MIME_TYPES,
  DocumentDtoSchema,
  MAX_UPLOAD_BYTES,
  OnboardingDashboardSchema,
  PresignedUploadSchema,
  PresignedViewSchema,
  ProfileSectionDtoSchema,
  type AllowedUploadMimeType,
  type DocumentDto,
  type DocumentType,
  type OnboardingDashboard,
  type PresignedView,
  type ProfileSectionDto,
  type SectionKey,
} from '@/lib/contract';
import { ApiError, apiFetch } from './client';

export function getDashboard(signal?: AbortSignal): Promise<OnboardingDashboard> {
  return apiFetch('/me/onboarding', { schema: OnboardingDashboardSchema, signal });
}

export function saveSection(key: SectionKey, data: Record<string, unknown>): Promise<ProfileSectionDto> {
  return apiFetch(`/me/onboarding/sections/${key}`, {
    method: 'PUT',
    body: { data },
    schema: ProfileSectionDtoSchema,
  });
}

export function getDocumentViewUrl(id: string): Promise<PresignedView> {
  return apiFetch(`/me/onboarding/documents/${id}/url`, { schema: PresignedViewSchema });
}

export function submitOnboarding(): Promise<OnboardingDashboard> {
  return apiFetch('/me/onboarding/submit', { method: 'POST', schema: OnboardingDashboardSchema });
}

function isAllowedMime(type: string): type is AllowedUploadMimeType {
  return (ALLOWED_UPLOAD_MIME_TYPES as readonly string[]).includes(type);
}

/**
 * Full document upload: request a presigned PUT, upload the file directly to storage
 * (with progress), then confirm so the server hashes it. Validates type/size first.
 */
export async function uploadDocument(
  file: File,
  sectionKey: SectionKey,
  docType: DocumentType,
  onProgress?: (percent: number) => void,
): Promise<DocumentDto> {
  if (!isAllowedMime(file.type)) {
    throw new ApiError(400, 'Unsupported file type — use PDF, PNG, or JPEG');
  }
  if (file.size > MAX_UPLOAD_BYTES) {
    throw new ApiError(400, 'File is too large (max 10 MB)');
  }

  const presign = PresignedUploadSchema.parse(
    await apiFetch('/me/onboarding/documents', {
      method: 'POST',
      body: {
        sectionKey,
        docType,
        fileName: file.name,
        mimeType: file.type,
        sizeBytes: file.size,
      },
    }),
  );

  await putWithProgress(presign.uploadUrl, file, presign.headers, onProgress);

  return apiFetch(`/me/onboarding/documents/${presign.documentId}/confirm`, {
    method: 'POST',
    schema: DocumentDtoSchema,
  });
}

/** Direct PUT to object storage with upload progress (fetch lacks upload progress). */
function putWithProgress(
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
