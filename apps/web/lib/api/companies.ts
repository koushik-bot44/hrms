import type {
  CompanyDetail,
  CompanySummary,
  CreateCompanyInput,
  ProvisionCompanyAdminInput,
  ProvisionCompanyAdminResult,
  UpdateCompanyInput,
} from '@/lib/contract';
import { apiFetch } from './client';

export function listCompanies(signal?: AbortSignal): Promise<CompanySummary[]> {
  return apiFetch<CompanySummary[]>('/companies', { signal });
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
