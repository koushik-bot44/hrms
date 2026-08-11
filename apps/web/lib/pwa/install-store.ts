/**
 * A tiny module-level store for the browser's install prompt (§ PWA). `beforeinstallprompt` can fire early
 * (e.g. on the sign-in screen, before the authenticated shell mounts), so {@link PwaRuntime} starts capturing
 * it on EVERY app route and stashes the (single) deferred event here; the post-sign-in nudge reads it via
 * {@link subscribeInstall}/{@link getInstallEvent}. Client-only — a no-op during SSR.
 */

export interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

let deferred: BeforeInstallPromptEvent | null = null;
let started = false;
const subscribers = new Set<() => void>();

function emit() {
  subscribers.forEach((fn) => fn());
}

/** Begin capturing the install prompt (idempotent). Call from an always-mounted client runtime. */
export function startInstallCapture(): void {
  if (started || typeof window === 'undefined') return;
  started = true;
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault(); // stop Chrome's default mini-infobar; we show our own nudge
    deferred = e as BeforeInstallPromptEvent;
    emit();
  });
  window.addEventListener('appinstalled', () => {
    deferred = null;
    emit();
  });
}

export function getInstallEvent(): BeforeInstallPromptEvent | null {
  return deferred;
}

export function clearInstallEvent(): void {
  deferred = null;
  emit();
}

export function subscribeInstall(cb: () => void): () => void {
  subscribers.add(cb);
  return () => {
    subscribers.delete(cb);
  };
}
