import { HealthResponseSchema, type HealthResponse } from '@/lib/contract';
import { apiFetch } from './client';

/**
 * GET /health — parsed through the shared zod schema, so the result is a
 * validated `HealthResponse` ({ status, db }) straight from @/lib/contract.
 */
export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return apiFetch('/health', { schema: HealthResponseSchema, signal });
}
