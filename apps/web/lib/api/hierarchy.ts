import type {
  HierarchyStatus,
  ProvisionHierarchyInput,
  ProvisionHierarchyResult,
} from '@/lib/contract';
import { apiFetch } from './client';

// --- Provisioning (SUPER_ADMIN) -------------------------------------------

/** Whether the singleton Hierarchy exists — drives the Super Admin "Hierarchy" provisioning UI. */
export function getHierarchyStatus(signal?: AbortSignal): Promise<HierarchyStatus> {
  return apiFetch<HierarchyStatus>('/provisioning/hierarchy', { signal });
}

export function provisionHierarchy(body: ProvisionHierarchyInput): Promise<ProvisionHierarchyResult> {
  return apiFetch<ProvisionHierarchyResult>('/provisioning/hierarchy', { method: 'POST', body });
}

/** Remove the current Hierarchy so a replacement can be provisioned. */
export function removeHierarchy(): Promise<HierarchyStatus> {
  return apiFetch<HierarchyStatus>('/provisioning/hierarchy', { method: 'DELETE' });
}
