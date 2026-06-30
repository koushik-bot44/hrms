import type {
  EmployeeSummary,
  OnboardEmployeeInput,
  OnboardEmployeeResult,
} from '@/lib/contract';
import { apiFetch } from './client';

export function listMyEmployees(signal?: AbortSignal): Promise<EmployeeSummary[]> {
  return apiFetch<EmployeeSummary[]>('/employees', { signal });
}

export function onboardEmployee(body: OnboardEmployeeInput): Promise<OnboardEmployeeResult> {
  return apiFetch<OnboardEmployeeResult>('/employees', { method: 'POST', body });
}
