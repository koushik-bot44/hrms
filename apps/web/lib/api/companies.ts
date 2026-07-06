import type {
  CompanyDetail,
  CompanySummary,
  CreateCompanyInput,
  ProvisionCompanyAdminInput,
  ProvisionCompanyAdminResult,
  PurgeCompanyResult,
  UpdateCompanyInput,
} from '@/lib/contract';
import { apiFetch } from './client';

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
