import { apiFetch } from './client';

/**
 * Web Push client (§ Web Push, Stage N1). Thin wrappers over the /push endpoints. Types mirror the
 * Spring Boot DTOs (PushDtos); the OpenAPI schema is the source of truth (see lib/api/types.ts).
 */

export interface PushPublicKey {
  publicKey: string;
  enabled: boolean;
}

export interface PushSubscribeResult {
  id: string;
  existing: boolean;
}

export interface PushTestResult {
  targeted: number;
  message: string;
}

export interface PushSubscribeInput {
  endpoint: string;
  keys: { p256dh: string; auth: string };
  userAgent?: string;
}

export function getPushPublicKey(signal?: AbortSignal): Promise<PushPublicKey> {
  return apiFetch<PushPublicKey>('/push/public-key', { signal });
}

export function subscribePush(body: PushSubscribeInput): Promise<PushSubscribeResult> {
  return apiFetch<PushSubscribeResult>('/push/subscribe', { method: 'POST', body });
}

export function unsubscribePush(endpoint: string): Promise<void> {
  return apiFetch<void>('/push/unsubscribe', { method: 'DELETE', body: { endpoint } });
}

export function sendPushTest(): Promise<PushTestResult> {
  return apiFetch<PushTestResult>('/push/test', { method: 'POST' });
}
