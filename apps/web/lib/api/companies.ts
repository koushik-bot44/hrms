import type {
  CompanyDetail,
  CompanySummary,
  CreateCompanyInput,
  Letterhead,
  LetterheadPartName,
  LetterheadUpload,
  ProvisionCompanyAdminInput,
  ProvisionCompanyAdminResult,
  PurgeCompanyResult,
  UpdateCompanyInput,
} from '@/lib/contract';
import {
  LETTERHEAD_ACCEPT,
  LETTERHEAD_MAX_BYTES,
  LETTERHEAD_MIN_WIDTH_PX,
} from '@/lib/contract';
import { apiFetch, ApiError } from './client';

export function listCompanies(signal?: AbortSignal): Promise<CompanySummary[]> {
  return apiFetch<CompanySummary[]>('/companies', { signal });
}

/** Archived (soft-deleted) companies (Super Admin). */
export function listDeletedCompanies(signal?: AbortSignal): Promise<CompanySummary[]> {
  return apiFetch<CompanySummary[]>('/companies?deleted=true', { signal });
}

/** Archive a company (reversible). Its people immediately lose access. */
export function deleteCompany(id: string): Promise<CompanyDetail> {
  return apiFetch<CompanyDetail>(`/companies/${id}`, { method: 'DELETE' });
}

/** Restore an archived company back to active. */
export function restoreCompany(id: string): Promise<CompanyDetail> {
  return apiFetch<CompanyDetail>(`/companies/${id}/restore`, { method: 'POST' });
}

/** PERMANENTLY delete a company and all its data. Irreversible — not a soft-delete. */
export function purgeCompany(id: string): Promise<PurgeCompanyResult> {
  return apiFetch<PurgeCompanyResult>(`/companies/${id}/purge`, { method: 'DELETE' });
}

export function getCompany(id: string, signal?: AbortSignal): Promise<CompanyDetail> {
  return apiFetch<CompanyDetail>(`/companies/${id}`, { signal });
}

export function createCompany(body: CreateCompanyInput): Promise<CompanyDetail> {
  return apiFetch<CompanyDetail>('/companies', { method: 'POST', body });
}

export function updateCompany(id: string, body: UpdateCompanyInput): Promise<CompanyDetail> {
  return apiFetch<CompanyDetail>(`/companies/${id}`, { method: 'PATCH', body });
}

export function provisionCompanyAdmin(
  id: string,
  body: ProvisionCompanyAdminInput,
): Promise<ProvisionCompanyAdminResult> {
  return apiFetch<ProvisionCompanyAdminResult>(`/companies/${id}/admin`, { method: 'POST', body });
}

// --- Per-company letterhead (§3.5, SUPER_ADMIN) ----------------------------

/** Current letterhead metadata (header/footer, null when unset) + presigned previews. */
export function getLetterhead(id: string, signal?: AbortSignal): Promise<Letterhead> {
  return apiFetch<Letterhead>(`/companies/${id}/letterhead`, { signal });
}

/** Mirror the server rules so bad files fail fast with a clear message (server re-validates authoritatively). */
export function validateLetterheadFile(file: File): string | null {
  if (!(LETTERHEAD_ACCEPT as readonly string[]).includes(file.type)) {
    return 'Upload a PNG or JPG image.';
  }
  if (file.size > LETTERHEAD_MAX_BYTES) {
    return 'Image must be 5 MB or smaller.';
  }
  return null;
}

async function imageWidth(file: File): Promise<number> {
  const url = URL.createObjectURL(file);
  try {
    return await new Promise<number>((resolve, reject) => {
      const img = new Image();
      img.onload = () => resolve(img.naturalWidth);
      img.onerror = () => reject(new Error('decode'));
      img.src = url;
    });
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * Upload (or replace) a letterhead part via the presigned handshake: validate → presigned PUT → confirm.
 * Returns the updated letterhead. Applies to documents generated from now on; existing PDFs are unchanged.
 */
export async function uploadLetterhead(
  id: string,
  part: LetterheadPartName,
  file: File,
): Promise<Letterhead> {
  const err = validateLetterheadFile(file);
  if (err) {
    throw new ApiError(400, err);
  }
  let width = 0;
  try {
    width = await imageWidth(file);
  } catch {
    throw new ApiError(400, 'That image could not be read — try a different PNG or JPG.');
  }
  if (width < LETTERHEAD_MIN_WIDTH_PX) {
    throw new ApiError(400, `Image must be at least ${LETTERHEAD_MIN_WIDTH_PX}px wide for print quality.`);
  }

  const presign = await apiFetch<LetterheadUpload>(`/companies/${id}/letterhead/${part}/begin-upload`, {
    method: 'POST',
    body: { contentType: file.type, sizeBytes: file.size },
  });
  const put = await fetch(presign.uploadUrl, {
    method: presign.method ?? 'PUT',
    headers: presign.headers,
    body: file,
  });
  if (!put.ok) {
    throw new ApiError(put.status, 'The upload failed — please try again.');
  }
  return apiFetch<Letterhead>(`/companies/${id}/letterhead/${part}/confirm`, { method: 'POST' });
}

/** Revert a letterhead part to the plain layout. */
export function removeLetterhead(id: string, part: LetterheadPartName): Promise<Letterhead> {
  return apiFetch<Letterhead>(`/companies/${id}/letterhead/${part}`, { method: 'DELETE' });
}
