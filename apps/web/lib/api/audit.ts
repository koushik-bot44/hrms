import type { AuditLogPage } from '@/lib/contract';
import { apiFetch } from './client';

export interface AuditQuery {
  companyId?: string;
  action?: string;
  actorType?: string;
  from?: string; // ISO instant
  to?: string; // ISO instant
  page?: number;
  size?: number;
}

/** Per-company audit trail (§7). Company Admin omits companyId (the API locks to their own). */
export function getAuditLogs(query: AuditQuery, signal?: AbortSignal): Promise<AuditLogPage> {
  const params = new URLSearchParams();
  if (query.companyId) params.set('companyId', query.companyId);
  if (query.action) params.set('action', query.action);
  if (query.actorType) params.set('actorType', query.actorType);
  if (query.from) params.set('from', query.from);
  if (query.to) params.set('to', query.to);
  params.set('page', String(query.page ?? 0));
  params.set('size', String(query.size ?? 25));
  return apiFetch<AuditLogPage>(`/audit?${params.toString()}`, { signal });
}
