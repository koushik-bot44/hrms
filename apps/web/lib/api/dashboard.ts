import type { DashboardSummary } from '@/lib/contract';
import { apiFetch } from './client';

/** The current principal's role-scoped dashboard summary (stats + recent activity). */
export function getDashboardSummary(signal?: AbortSignal): Promise<DashboardSummary> {
  return apiFetch<DashboardSummary>('/dashboard/summary', { signal });
}
