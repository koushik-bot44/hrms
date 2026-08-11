'use client';

import * as React from 'react';
import { toast } from 'sonner';
import { Bell, Download, Share, X } from 'lucide-react';
import { useAuth } from '@/components/auth-provider';
import { enablePush, getPushState, isPushSupported } from '@/lib/push/registration';
import {
  clearInstallEvent,
  getInstallEvent,
  subscribeInstall,
} from '@/lib/pwa/install-store';
import { Button } from '@/components/ui/button';

const DISMISS_KEY = 'ihrms.appPromptsDismissed';

function isStandalone(): boolean {
  if (typeof window === 'undefined') return false;
  return (
    window.matchMedia?.('(display-mode: standalone)').matches ||
    // iOS Safari standalone flag.
    (window.navigator as unknown as { standalone?: boolean }).standalone === true
  );
}

function isIosSafari(): boolean {
  if (typeof navigator === 'undefined') return false;
  const ua = navigator.userAgent;
  const ios = /iphone|ipad|ipod/i.test(ua);
  const webkit = /webkit/i.test(ua) && !/crios|fxios|edgios|opios/i.test(ua); // real Safari, not Chrome/FF on iOS
  return ios && webkit;
}

/**
 * Post-sign-in nudge (mounted once in {@link AppShell}, so it covers every authenticated area): offers to
 * **turn on notifications** (a mailbox principal only — clicking fires the browser's native permission prompt)
 * and to **add hrorg.in to the home screen** (Chrome/Android via `beforeinstallprompt`; iOS Safari shows the
 * Share → "Add to Home Screen" hint). Appears shortly after landing, is dismissible, and remembers dismissal so
 * it never nags. Both actions also stay available elsewhere (Notifications in the user menu; the browser's own
 * install control).
 */
export function AppPrompts() {
  const { session, status } = useAuth();
  // The install prompt is captured app-wide by PwaRuntime (it can fire on the sign-in screen) — read it here.
  const installEvent = React.useSyncExternalStore(subscribeInstall, getInstallEvent, () => null);
  const [pushPermission, setPushPermission] = React.useState<string>('unsupported');
  const [visible, setVisible] = React.useState(false);
  const [dismissed, setDismissed] = React.useState(true); // assume dismissed until we read storage

  const hasMailbox =
    session?.type === 'USER' ||
    (session?.type === 'EMPLOYEE' && Boolean(session.mailAddress));
  const standalone = isStandalone();
  const iosSafari = isIosSafari();

  // Read prior dismissal + the current push permission (never prompts here).
  React.useEffect(() => {
    try {
      setDismissed(localStorage.getItem(DISMISS_KEY) === '1');
    } catch {
      setDismissed(false);
    }
    if (isPushSupported()) {
      getPushState()
        .then((s) => setPushPermission(s.permission))
        .catch(() => setPushPermission('default'));
    }
  }, []);

  const offerNotifications = hasMailbox && isPushSupported() && pushPermission === 'default';
  const offerInstall = !standalone && (Boolean(installEvent) || iosSafari);

  // Show a short moment after sign-in, only when there is something to offer and it wasn't dismissed.
  React.useEffect(() => {
    if (status !== 'authenticated' || dismissed) return;
    if (!offerNotifications && !offerInstall) return;
    const t = setTimeout(() => setVisible(true), 1500);
    return () => clearTimeout(t);
  }, [status, dismissed, offerNotifications, offerInstall]);

  const close = (remember = true) => {
    setVisible(false);
    if (remember) {
      try {
        localStorage.setItem(DISMISS_KEY, '1');
      } catch {
        /* ignore */
      }
      setDismissed(true);
    }
  };

  const turnOnNotifications = async () => {
    try {
      const next = await enablePush();
      setPushPermission(next.permission);
      if (next.subscribed) {
        toast.success('Notifications enabled on this device.');
      }
      if (next.permission !== 'default') {
        // Decided (granted or blocked) — nothing more to offer here.
        if (!offerInstall) close();
      }
    } catch (e) {
      toast.error(e instanceof Error ? e.message : 'Could not enable notifications.');
    }
  };

  const addToHomeScreen = async () => {
    if (!installEvent) return;
    await installEvent.prompt();
    const choice = await installEvent.userChoice.catch(() => ({ outcome: 'dismissed' as const }));
    clearInstallEvent(); // the prompt can only be used once
    if (choice.outcome === 'accepted') {
      toast.success('Installing hrorg.in…');
      close();
    }
  };

  if (!visible) return null;

  return (
    <div
      className="fixed inset-x-0 bottom-0 z-50 flex justify-center px-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] sm:inset-x-auto sm:right-5 sm:bottom-5 sm:px-0"
      role="dialog"
      aria-label="Get more from hrorg.in"
    >
      <div className="animate-page-enter w-full max-w-sm rounded-2xl border bg-card p-4 shadow-2xl shadow-black/20">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <p className="text-sm font-semibold">Get more from hrorg.in</p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              Stay in the loop and open it like an app.
            </p>
          </div>
          <button
            type="button"
            onClick={() => close()}
            aria-label="Dismiss"
            className="-mr-1 -mt-1 rounded-full p-1.5 text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
          >
            <X className="size-4" />
          </button>
        </div>

        <div className="mt-3 space-y-2.5">
          {offerNotifications ? (
            <div className="flex items-center justify-between gap-3 rounded-xl bg-muted/50 p-2.5">
              <div className="flex min-w-0 items-center gap-2.5">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary">
                  <Bell className="size-4" />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-medium">Turn on notifications</p>
                  <p className="truncate text-xs text-muted-foreground">Alerts even when the tab is closed.</p>
                </div>
              </div>
              <Button type="button" size="sm" onClick={turnOnNotifications}>
                Enable
              </Button>
            </div>
          ) : null}

          {offerInstall ? (
            <div className="flex items-center justify-between gap-3 rounded-xl bg-muted/50 p-2.5">
              <div className="flex min-w-0 items-center gap-2.5">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-primary/15 text-primary">
                  <Download className="size-4" />
                </span>
                <div className="min-w-0">
                  <p className="text-sm font-medium">Add to Home Screen</p>
                  {iosSafari && !installEvent ? (
                    <p className="text-xs text-muted-foreground">
                      Tap <Share className="inline size-3 align-[-2px]" /> Share, then{' '}
                      <span className="font-medium text-foreground">Add to Home Screen</span>.
                    </p>
                  ) : (
                    <p className="truncate text-xs text-muted-foreground">Open hrorg.in like an app.</p>
                  )}
                </div>
              </div>
              {installEvent ? (
                <Button type="button" size="sm" variant="outline" onClick={addToHomeScreen}>
                  Add
                </Button>
              ) : (
                <Button type="button" size="sm" variant="ghost" onClick={() => close()}>
                  Got it
                </Button>
              )}
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
