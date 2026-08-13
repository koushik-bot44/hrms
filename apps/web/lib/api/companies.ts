import type {
  CompanyDetail,
  CompanySummary,
  CreateCompanyInput,
  Letterhead,
  LetterheadMargins,
  LetterheadUpload,
  ProvisionCompanyAdminInput,
  ProvisionCompanyAdminResult,
  PurgeCompanyResult,
  UpdateCompanyInput,
} from '@/lib/contract';
import {
  LETTERHEAD_MAX_BYTES,
  LETTERHEAD_PDF_TYPE,
  LETTERHEAD_WORD_TYPES,
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

// --- Per-company letterhead (§3.5, SUPER_ADMIN; document + margin box) ------

/** Current letterhead: present flag, page size + margin box (points), a presigned preview + capability flag. */
export function getLetterhead(id: string, signal?: AbortSignal): Promise<Letterhead> {
  return apiFetch<Letterhead>(`/companies/${id}/letterhead`, { signal });
}

/** Mirror the server rules so bad files fail fast (server re-validates authoritatively). */
export function validateLetterheadFile(file: File, wordAllowed: boolean): string | null {
  const isPdf = file.type === LETTERHEAD_PDF_TYPE || /\.pdf$/i.test(file.name);
  const isWord =
    (LETTERHEAD_WORD_TYPES as readonly string[]).includes(file.type) || /\.docx?$/i.test(file.name);
  if (!isPdf && !isWord) {
    return 'Upload a PDF or Word (.docx/.doc) letterhead.';
  }
  if (isWord && !wordAllowed) {
    return 'Word conversion isn’t available on this server. Please export your letterhead to PDF and upload the PDF.';
  }
  if (file.size > LETTERHEAD_MAX_BYTES) {
    return 'The letterhead must be 10 MB or smaller.';
  }
  return null;
}

/** The declared content type for the presigned PUT: honour the browser's type, else infer from the extension. */
function letterheadContentType(file: File): string {
  if (file.type) {
    return file.type;
  }
  if (/\.pdf$/i.test(file.name)) {
    return LETTERHEAD_PDF_TYPE;
  }
  if (/\.docx$/i.test(file.name)) {
    return LETTERHEAD_WORD_TYPES[0];
  }
  return 'application/msword';
}

/**
 * Upload (or replace) the letterhead via the presigned handshake: validate → presigned PUT → confirm. The
 * server converts Word→PDF, uses the first page, rasterizes a preview and seeds default margins. Returns the
 * updated letterhead. Applies to documents generated from now on; existing PDFs are unchanged.
 */
export async function uploadLetterhead(id: string, file: File, wordAllowed: boolean): Promise<Letterhead> {
  const err = validateLetterheadFile(file, wordAllowed);
  if (err) {
    throw new ApiError(400, err);
  }
  const presign = await apiFetch<LetterheadUpload>(`/companies/${id}/letterhead/begin-upload`, {
    method: 'POST',
    body: { contentType: letterheadContentType(file), sizeBytes: file.size },
  });
  const put = await fetch(presign.uploadUrl, {
    method: presign.method ?? 'PUT',
    headers: presign.headers,
    body: file,
  });
  if (!put.ok) {
    throw new ApiError(put.status, 'The upload failed — please try again.');
  }
  return apiFetch<Letterhead>(`/companies/${id}/letterhead/confirm`, { method: 'POST' });
}

/** Save the content margin box (points). */
export function saveLetterheadMargins(id: string, margins: LetterheadMargins): Promise<Letterhead> {
  return apiFetch<Letterhead>(`/companies/${id}/letterhead/margins`, { method: 'PUT', body: margins });
}

/** Reset the margin box to the sensible defaults for the page. */
export function resetLetterheadMargins(id: string): Promise<Letterhead> {
  return apiFetch<Letterhead>(`/companies/${id}/letterhead/margins/reset`, { method: 'POST' });
}

/** Remove the letterhead — documents render plain again. */
export function removeLetterhead(id: string): Promise<Letterhead> {
  return apiFetch<Letterhead>(`/companies/${id}/letterhead`, { method: 'DELETE' });
}
