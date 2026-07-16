'use client';

import * as React from 'react';
import { toast } from 'sonner';
import { Bell, BellOff, BellRing, Send } from 'lucide-react';
import { sendPushTest } from '@/lib/api/push';
import {
  disablePush,
  enablePush,
  getPushState,
  isPushSupported,
  type PushState,
} from '@/lib/push/registration';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * OS-notification opt-in (§ Web Push, Stage N2). Registers the push Service Worker and drives
 * enable/disable via the N1 endpoints. Permission is requested ONLY on an explicit click (never on load);
 * once the browser has BLOCKED notifications we cannot re-prompt, so that state shows guidance instead of
 * a button. Shown to any mailbox principal (staff + credentialed employees).
 */
export function NotificationsDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (v: boolean) => void;
}) {
  const [state, setState] = React.useState<PushState | null>(null);
  const [busy, setBusy] = React.useState(false);
  const [note, setNote] = React.useState<string | null>(null);

  // Read the current permission + subscription when the dialog opens. NEVER prompts.
  React.useEffect(() => {
    if (!open) return;
    setNote(null);
    setState(null);
    getPushState()
      .then(setState)
      .catch(() =>
        setState({
          supported: isPushSupported(),
          permission: 'default',
          subscribed: false,
        }),
      );
  }, [open]);

  const enable = async () => {
    setBusy(true);
    setNote(null);
    try {
      const next = await enablePush();
      setState(next);
      if (next.subscribed) {
        toast.success('Notifications enabled on this device.');
      } else if (next.permission === 'default') {
        setNote('Permission was dismissed — you can enable notifications any time.');
      }
      // 'denied' is handled by the rendered state below (no nagging).
    } catch (e) {
      setNote(e instanceof Error ? e.message : 'Could not enable notifications.');
    } finally {
      setBusy(false);
    }
  };

  const disable = async () => {
    setBusy(true);
    setNote(null);
    try {
      setState(await disablePush());
      toast.success('Notifications turned off on this device.');
    } catch (e) {
      setNote(e instanceof Error ? e.message : 'Could not turn off notifications.');
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
          <DialogTitle>Notifications</DialogTitle>
          <DialogDescription>
            Get IHRMS alerts as system notifications — even when this tab is minimized or closed.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-3">
          {state === null ? (
            <p className="text-sm text-muted-foreground">Checking…</p>
          ) : !state.supported ? (
            <p className="text-sm text-muted-foreground">
              This browser doesn&rsquo;t support notifications.
            </p>
          ) : state.permission === 'denied' ? (
            <p className="text-sm text-muted-foreground">
              Notifications are <span className="font-medium text-foreground">blocked</span> for this site
              in your browser. To turn them on, allow notifications for this site in your browser&rsquo;s
              settings, then reopen this dialog. (We can&rsquo;t ask again once it&rsquo;s blocked.)
            </p>
          ) : state.subscribed ? (
            <>
              <p className="flex items-center gap-2 text-sm text-foreground">
                <BellRing className="size-4 text-success" />
                Notifications are on for this device.
              </p>
              <div className="flex flex-wrap gap-2">
                <Button type="button" variant="secondary" onClick={test} disabled={busy}>
                  <Send className="size-4" />
                  Send test
                </Button>
                <Button type="button" variant="outline" onClick={disable} disabled={busy}>
                  <BellOff className="size-4" />
                  Turn off
                </Button>
              </div>
              <p className="text-xs text-muted-foreground">
                Tip: click <span className="font-medium">Send test</span>, then minimize or close this tab
                and send again to confirm background delivery.
              </p>
            </>
          ) : (
            <Button type="button" onClick={enable} disabled={busy}>
              <Bell className="size-4" />
              Enable notifications
            </Button>
          )}
          {note ? <p className="text-sm text-destructive">{note}</p> : null}
        </div>
      </DialogContent>
    </Dialog>
  );
}
