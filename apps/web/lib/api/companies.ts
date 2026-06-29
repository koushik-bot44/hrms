import {
  CompanyDetailSchema,
  CompanyListSchema,
  ProvisionCompanyAdminResultSchema,
  type CompanyDetail,
  type CompanySummary,
  type CreateCompanyInput,
  type ProvisionCompanyAdminInput,
  type ProvisionCompanyAdminResult,
  type UpdateCompanyInput,
} from '@/lib/contract';
import { apiFetch } from './client';

export function listCompanies(signal?: AbortSignal): Promise<CompanySummary[]> {
  return apiFetch('/companies', { schema: CompanyListSchema, signal });
}

export function getCompany(id: string, signal?: AbortSignal): Promise<CompanyDetail> {
  return apiFetch(`/companies/${id}`, { schema: CompanyDetailSchema, signal });
}

export function createCompany(body: CreateCompanyInput): Promise<CompanyDetail> {
  return apiFetch('/companies', { method: 'POST', body, schema: CompanyDetailSchema });
}

export function updateCompany(id: string, body: UpdateCompanyInput): Promise<CompanyDetail> {
  return apiFetch(`/companies/${id}`, { method: 'PATCH', body, schema: CompanyDetailSchema });
}

export function provisionCompanyAdmin(
  id: string,
  body: ProvisionCompanyAdminInput,
): Promise<ProvisionCompanyAdminResult> {
  return apiFetch(`/companies/${id}/admin`, {
    method: 'POST',
    body,
    schema: ProvisionCompanyAdminResultSchema,
  });
}
