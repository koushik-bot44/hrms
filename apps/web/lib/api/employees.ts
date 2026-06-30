import type {
  EmployeePage,
  EmployeeStatus,
  OnboardEmployeeInput,
  OnboardEmployeeResult,
} from '@/lib/contract';
import { apiFetch } from './client';

export interface EmployeeQueueParams {
  search?: string;
  status?: EmployeeStatus | '';
  page?: number;
  size?: number;
}

/** The HR's onboarding queue: own onboarded employees, name/email search + status, paginated. */
export function getEmployeeQueue(
  params: EmployeeQueueParams = {},
  signal?: AbortSignal,
): Promise<EmployeePage> {
  const q = new URLSearchParams();
  if (params.search) q.set('search', params.search);
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page ?? 0));
  q.set('size', String(params.size ?? 20));
  return apiFetch<EmployeePage>(`/employees?${q.toString()}`, { signal });
}

export function onboardEmployee(body: OnboardEmployeeInput): Promise<OnboardEmployeeResult> {
  return apiFetch<OnboardEmployeeResult>('/employees', { method: 'POST', body });
}
