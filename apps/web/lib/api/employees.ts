import type {
  EmployeePage,
  EmployeeStatus,
  Form2Values,
  Form2View,
  OfferTermsInput,
  OnboardEmployeeInput,
  OnboardEmployeeResult,
  SuperAdminOnboardInput,
} from '@/lib/contract';
import { apiFetch } from './client';

export interface EmployeeQueueParams {
  search?: string;
  status?: EmployeeStatus | '';
  page?: number;
  size?: number;
}

function queryString(params: EmployeeQueueParams): string {
  const q = new URLSearchParams();
  if (params.search) q.set('search', params.search);
  if (params.status) q.set('status', params.status);
  q.set('page', String(params.page ?? 0));
  q.set('size', String(params.size ?? 20));
  return q.toString();
}

/** The HR's onboarding queue: own onboarded employees, name/email search + status, paginated. */
export function getEmployeeQueue(
  params: EmployeeQueueParams = {},
  signal?: AbortSignal,
): Promise<EmployeePage> {
  return apiFetch<EmployeePage>(`/employees?${queryString(params)}`, { signal });
}

/** SUPER_ADMIN browses a chosen company's employees (all teams) — the SA forms-viewer navigation (§2). */
export function getCompanyEmployees(
  companyId: string,
  params: EmployeeQueueParams = {},
  signal?: AbortSignal,
): Promise<EmployeePage> {
  return apiFetch<EmployeePage>(`/companies/${companyId}/employees?${queryString(params)}`, { signal });
}

/**
 * Onboard = HR fills FORM 2 — Employee Info (§3.2). Submitting it creates the record and sends the
 * invite to the personal email (the login/OTP identity). No employee ID is minted here (allocated on
 * Manager approval, §5).
 */
export function onboardEmployee(input: OnboardEmployeeInput): Promise<OnboardEmployeeResult> {
  const { salary, location, ...form2 } = input;
  return apiFetch<OnboardEmployeeResult>('/employees', {
    method: 'POST',
    body: { form2, offer: { salary, location: location || undefined } },
  });
}

/** SUPER_ADMIN onboards into a chosen company by selecting a team (its HR is resolved server-side). */
export function onboardForCompany(
  companyId: string,
  body: { teamId: string; form2: Form2Values; offer: OfferTermsInput },
): Promise<OnboardEmployeeResult> {
  return apiFetch<OnboardEmployeeResult>(`/companies/${companyId}/employees`, {
    method: 'POST',
    body,
  });
}

/**
 * Edit an INVITED employee's Form 2 (§3.2) — HR (own onboarded) / SUPER_ADMIN (any). The API rejects a
 * manual edit once the employee starts onboarding (409). Changing the personal email re-sends the invite
 * to the new address.
 */
export function editEmployeeForm2(id: string, body: Form2Values): Promise<Form2View> {
  return apiFetch<Form2View>(`/employees/${encodeURIComponent(id)}/form2`, {
    method: 'PATCH',
    body,
  });
}

/** Re-exported for the Super Admin's `{ teamId, form2 }` onboard body. */
export type { SuperAdminOnboardInput };
