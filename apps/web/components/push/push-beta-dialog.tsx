'use client';

import * as React from 'react';
import { toast } from 'sonner';
import { Bell, BellOff, Send } from 'lucide-react';
import {
  getPushPublicKey,
  sendPushTest,
  subscribePush,
  unsubscribePush,
} from '@/lib/api/push';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/** Convert a base64url VAPID key into the Uint8Array the Push API expects for applicationServerKey. */
function urlBase64ToUint8Array(base64: string): Uint8Array<ArrayBuffer> {
  const padding = '='.repeat((4 - (base64.length % 4)) % 4);
  const normalized = (base64 + padding).replace(/-/g, '+').replace(/_/g, '/');
  const raw = atob(normalized);
  const out = new Uint8Array(new ArrayBuffer(raw.length));
  for (let i = 0; i < raw.length; i += 1) out[i] = raw.charCodeAt(i);
  return out;
}

const supported = () =>
  typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window;

/**
 * N1 verification affordance for OS-level Web Push (beta). NOT the real opt-in UX — that is Stage N2.
 * It requests permission, registers the minimal /sw.js, subscribes via the Push API using the server's
 * VAPID public key, POSTs the subscription, and can fire a self-test. Marked clearly as beta.
 */
export function PushBetaDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
}) {
  const [busy, setBusy] = React.useState(false);
  const [subscribed, setSubscribed] = React.useState(false);
  const [note, setNote] = React.useState<string | null>(null);

  // Reflect the current subscription state when the dialog opens.
  React.useEffect(() => {
    if (!open || !supported()) return;
    navigator.serviceWorker
      .getRegistration()
      .then((reg) => reg?.pushManager.getSubscription())
      .then((sub) => setSubscribed(Boolean(sub)))
      .catch(() => {});
  }, [open]);

  const enable = async () => {
    if (!supported()) {
      setNote('This browser does not support Web Push.');
      return;
    }
    setBusy(true);
    setNote(null);
    try {
      const permission = await Notification.requestPermission();
      if (permission !== 'granted') {
        setNote('Notification permission was not granted.');
        return;
      }
      const key = await getPushPublicKey();
      if (!key.enabled || !key.publicKey) {
        setNote('Push is not configured on the server (no VAPID keys).');
        return;
      }
      const reg = await navigator.serviceWorker.register('/sw.js');
      await navigator.serviceWorker.ready;
      const sub = await reg.pushManager.subscribe({
        userVisibleOnly: true,
        applicationServerKey: urlBase64ToUint8Array(key.publicKey),
      });
      const json = sub.toJSON() as { endpoint?: string; keys?: { p256dh?: string; auth?: string } };
      await subscribePush({
        endpoint: json.endpoint ?? sub.endpoint,
        keys: { p256dh: json.keys?.p256dh ?? '', auth: json.keys?.auth ?? '' },
        userAgent: navigator.userAgent,
      });
      setSubscribed(true);
      toast.success('Notifications enabled on this device.');
    } catch (e) {
      setNote(e instanceof Error ? e.message : 'Could not enable notifications.');
    } finally {
      setBusy(false);
    }
  };

  const disable = async () => {
    if (!supported()) return;
    setBusy(true);
    setNote(null);
    try {
      const reg = await navigator.serviceWorker.getRegistration();
      const sub = await reg?.pushManager.getSubscription();
      if (sub) {
        await unsubscribePush(sub.endpoint).catch(() => {});
        await sub.unsubscribe();
      }
      setSubscribed(false);
      toast.success('Notifications disabled on this device.');
    } catch (e) {
      setNote(e instanceof Error ? e.message : 'Could not disable notifications.');
    } finally {
      setBusy(false);
    }
  };

  const test = async () => {
    setBusy(true);
    setNote(null);
    try {
      const result = await sendPushTest();
      toast.message(result.message);
    } catch (e) {
      setNote(e instanceof Error ? e.message : 'Could not send a test.');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>OS notifications (beta)</DialogTitle>
          <DialogDescription>
            Get IHRMS alerts as system notifications, even when this tab is in the background. This is an
            early preview used to verify delivery — the full experience is coming soon.
          </DialogDescription>
        </DialogHeader>

        {!supported() ? (
          <p className="text-sm text-muted-foreground">
            This browser doesn&rsquo;t support Web Push.
          </p>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap gap-2">
              {subscribed ? (
                <Button type="button" variant="outline" onClick={disable} disabled={busy}>
                  <BellOff className="size-4" />
                  Disable on this device
                </Button>
              ) : (
                <Button type="button" onClick={enable} disabled={busy}>
                  <Bell className="size-4" />
                  Enable notifications
                </Button>
              )}
              <Button type="button" variant="secondary" onClick={test} disabled={busy || !subscribed}>
                <Send className="size-4" />
                Send test
              </Button>
            </div>
            {note ? <p className="text-sm text-destructive">{note}</p> : null}
            {subscribed ? (
              <p className="text-xs text-muted-foreground">
                Subscribed on this device. Click <span className="font-medium">Send test</span>, then
                background the tab and send again to confirm true background delivery.
              </p>
            ) : null}
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
