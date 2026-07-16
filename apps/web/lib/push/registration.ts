import { getPushPublicKey, subscribePush, unsubscribePush } from '@/lib/api/push';

/**
 * Browser-side Web Push registration (§ Web Push, Stage N2). Registers the push-only Service Worker
 * (`/sw.js`) and drives the opt-in: request permission → subscribe via the Push API with the server's
 * VAPID public key → POST the subscription to the N1 endpoints (and the reverse to disable). Kept
 * separate from the API client (`lib/api/push.ts`, the /push HTTP calls) and the UI.
 */

const SW_URL = '/sw.js';

export type PushPermission = 'default' | 'granted' | 'denied' | 'unsupported';

export interface PushState {
  supported: boolean;
  permission: PushPermission;
  subscribed: boolean;
}

const UNSUPPORTED: PushState = { supported: false, permission: 'unsupported', subscribed: false };

/** Web Push needs a Service Worker, the Push API, and the Notification API — feature-detect all three. */
export function isPushSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    'serviceWorker' in navigator &&
    'PushManager' in window &&
    'Notification' in window
  );
}

/** Convert a base64url VAPID key into the Uint8Array the Push API expects for applicationServerKey. */
function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const normalized = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(normalized);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

async function registerServiceWorker(): Promise<ServiceWorkerRegistration> {
  const reg = await navigator.serviceWorker.register(SW_URL);
  await navigator.serviceWorker.ready;
  return reg;
}

/** True when an existing browser subscription was created with the given applicationServerKey. */
function boundToKey(sub: PushSubscription, serverKey: Uint8Array): boolean {
  const bound = sub.options?.applicationServerKey;
  if (!bound) return false; // unknown binding — treat as stale and re-subscribe fresh
  const a = new Uint8Array(bound);
  if (a.length !== serverKey.length) return false;
  for (let i = 0; i < a.length; i += 1) {
    if (a[i] !== serverKey[i]) return false;
  }
  return true;
}

/** Current permission + whether this browser already has an active subscription. Never prompts. */
export async function getPushState(): Promise<PushState> {
  if (!isPushSupported()) return UNSUPPORTED;
  const permission = Notification.permission as PushPermission;
  let subscribed = false;
  try {
    const reg = await navigator.serviceWorker.getRegistration();
    const sub = await reg?.pushManager.getSubscription();
    subscribed = Boolean(sub);
  } catch {
    // A registration/lookup failure just means "not subscribed" for display purposes.
  }
  return { supported: true, permission, subscribed };
}

/**
 * Enable OS notifications on THIS device. Requests permission (only call this on an explicit user action);
 * on grant, subscribes and registers the subscription server-side. Returns the resulting state — if the
 * user dismisses or blocks the prompt it comes back not-subscribed (permission 'default'/'denied').
 */
export async function enablePush(): Promise<PushState> {
  if (!isPushSupported()) return UNSUPPORTED;
  const permission = (await Notification.requestPermission()) as PushPermission;
  if (permission !== 'granted') {
    return { supported: true, permission, subscribed: false };
  }
  const key = await getPushPublicKey();
  if (!key.enabled || !key.publicKey) {
    throw new Error('Push is not configured on the server yet.');
  }
  const reg = await registerServiceWorker();
  const serverKey = urlBase64ToUint8Array(key.publicKey);
  // Reuse an existing subscription ONLY if it is bound to the CURRENT server key. After a VAPID key
  // rotation the old subscription can never be delivered to (the push service 410s it), so re-enabling
  // must drop it and subscribe fresh — otherwise Enable would keep re-registering a dead endpoint.
  let sub = await reg.pushManager.getSubscription();
  if (sub && !boundToKey(sub, serverKey)) {
    await sub.unsubscribe().catch(() => {});
    sub = null;
  }
  if (!sub) {
    sub = await reg.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: serverKey,
    });
  }
  const json = sub.toJSON() as { endpoint?: string; keys?: { p256dh?: string; auth?: string } };
  await subscribePush({
    endpoint: json.endpoint ?? sub.endpoint,
    keys: { p256dh: json.keys?.p256dh ?? '', auth: json.keys?.auth ?? '' },
    userAgent: navigator.userAgent,
  });
  return { supported: true, permission: 'granted', subscribed: true };
}

/** Disable on THIS device: unsubscribe from the Push API and remove the server row. */
export async function disablePush(): Promise<PushState> {
  if (!isPushSupported()) return UNSUPPORTED;
  const reg = await navigator.serviceWorker.getRegistration();
  const sub = await reg?.pushManager.getSubscription();
  if (sub) {
    // Remove the server row first (best-effort), then drop the browser subscription.
    await unsubscribePush(sub.endpoint).catch(() => {});
    await sub.unsubscribe();
  }
  return { supported: true, permission: Notification.permission as PushPermission, subscribed: false };
}
