import {
  EmployeeListSchema,
  OnboardEmployeeResultSchema,
  type EmployeeSummary,
  type OnboardEmployeeInput,
  type OnboardEmployeeResult,
} from '@/lib/contract';
import { apiFetch } from './client';

export function listMyEmployees(signal?: AbortSignal): Promise<EmployeeSummary[]> {
  return apiFetch('/employees', { schema: EmployeeListSchema, signal });
}

export function onboardEmployee(body: OnboardEmployeeInput): Promise<OnboardEmployeeResult> {
  return apiFetch('/employees', { method: 'POST', body, schema: OnboardEmployeeResultSchema });
}
