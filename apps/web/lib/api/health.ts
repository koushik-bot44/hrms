import { HealthResponseSchema, type HealthResponse } from '@ihrms/shared';
import { apiFetch } from './client';

/**
 * GET /health — parsed through the shared zod schema, so the result is a
 * validated `HealthResponse` ({ status, db }) straight from @ihrms/shared.
 */
export async function getHealth(signal?: AbortSignal): Promise<HealthResponse> {
  return apiFetch('/health', { schema: HealthResponseSchema, signal });
}
