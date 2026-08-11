'use client';

import * as React from 'react';
import { startInstallCapture } from '@/lib/pwa/install-store';

/**
 * Always-mounted PWA runtime (in the app root layout): registers the push/installability Service Worker and
 * starts capturing the install prompt on EVERY app route — so a `beforeinstallprompt` that fires on the sign-in
 * screen is still available to the post-sign-in "Add to Home Screen" nudge. Renders nothing.
 */
export function PwaRuntime() {
  React.useEffect(() => {
    startInstallCapture();
    if ('serviceWorker' in navigator) {
      navigator.serviceWorker.register('/sw.js').catch(() => {});
    }
  }, []);
  return null;
}
