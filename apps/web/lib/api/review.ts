import type {
  EmployeeRecord,
  ReviewInput,
  RouteToManagerInput,
  RouteToManagerResult,
} from '@/lib/contract';
import { apiFetch } from './client';

/** §3.4 lookup: the employee's full record (sections + documents with presigned view URLs). */
export function getEmployeeRecord(employeeCode: string, signal?: AbortSignal): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(`/employees/${encodeURIComponent(employeeCode)}`, { signal });
}

export function reviewSection(
  employeeCode: string,
  key: string,
  body: ReviewInput,
): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(
    `/employees/${encodeURIComponent(employeeCode)}/sections/${encodeURIComponent(key)}`,
    { method: 'PATCH', body },
  );
}

export function reviewDocument(
  employeeCode: string,
  documentId: string,
  body: ReviewInput,
): Promise<EmployeeRecord> {
  return apiFetch<EmployeeRecord>(
    `/employees/${encodeURIComponent(employeeCode)}/documents/${encodeURIComponent(documentId)}`,
    { method: 'PATCH', body },
  );
}

export function routeToManager(
  employeeCode: string,
  body: RouteToManagerInput,
): Promise<RouteToManagerResult> {
  return apiFetch<RouteToManagerResult>(
    `/employees/${encodeURIComponent(employeeCode)}/route-to-manager`,
    { method: 'POST', body },
  );
}
