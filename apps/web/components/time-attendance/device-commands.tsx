'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Check, Clock, RadioTower, Send, TriangleAlert, X } from 'lucide-react';
import {
  getCommandLog,
  getCommandStatus,
  iclockKeys,
  previewNameSync,
  pushName,
  syncDeviceTime,
  syncNames,
  type CommandStatus,
  type DeviceCommand,
  type NameSyncReport,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { istDateTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';

/**
 * The console surface for the device command channel — the one path that WRITES to hardware.
 *
 * <p>Every control here is an explicit button. Nothing queues as a side effect of saving a person,
 * which is the whole safety story: an operator asks for a push, by name, and the audit entry records
 * who asked.
 */

const STATUS_TONE: Record<DeviceCommand['status'], string> = {
  PENDING: 'text-muted-foreground',
  SENT: 'text-primary',
  ACKED: 'text-success',
  FAILED: 'text-destructive',
};

function StatusIcon({ status }: { status: DeviceCommand['status'] }) {
  if (status === 'ACKED') return <Check className="size-3.5" aria-hidden />;
  if (status === 'FAILED') return <X className="size-3.5" aria-hidden />;
  if (status === 'SENT') return <Send className="size-3.5" aria-hidden />;
  return <Clock className="size-3.5" aria-hidden />;
}

/**
 * Whether the channel is open, stated plainly.
 *
 * <p>Shown wherever a command can be queued. With the switch off a command still queues and simply
 * waits, which is correct but invisible — an operator who pressed a button and saw nothing happen
 * would reasonably conclude the button was broken.
 */
export function CommandChannelBadge() {
  const query = useApiQuery<CommandStatus>(
    iclockKeys.commandStatus(),
    (signal) => getCommandStatus(signal),
    { retry: false },
  );
  if (!query.data) return null;
  const { enabled, outstanding } = query.data;
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs',
        enabled
          ? 'border-success/30 bg-success/10 text-success'
          : 'border-border bg-muted text-muted-foreground',
      )}
      title={
        enabled
          ? 'Commands are being delivered to terminals.'
          : 'Commands will queue but nothing is sent until the channel is switched on.'
      }
    >
      <RadioTower className="size-3" aria-hidden />
      {enabled ? 'Device channel open' : 'Device channel closed'}
      {outstanding > 0 ? ` · ${outstanding} waiting` : null}
    </span>
  );
}

/** "Update on device(s)" — pushes one person's name to their building's terminals. */
export function PushNameButton({
  personId,
  disabled,
  className,
}: {
  personId: string;
  disabled?: boolean;
  className?: string;
}) {
  const qc = useQueryClient();
  const push = useApiMutation(() => pushName(personId), {
    successMessage: (queued: Array<Record<string, unknown>>) =>
      `Queued on ${queued.length} terminal${queued.length === 1 ? '' : 's'}. Device screens update on their next poll.`,
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.root }),
  });

  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      className={className}
      disabled={disabled || push.isPending}
      onClick={() => push.mutate()}
    >
      <RadioTower className="mr-1.5 size-4" aria-hidden />
      {push.isPending ? 'Queueing…' : 'Update on device'}
    </Button>
  );
}

/** "Sync time" — sets one terminal's clock from the server, in that building's timezone. */
export function SyncTimeButton({ deviceId }: { deviceId: string }) {
  const qc = useQueryClient();
  const sync = useApiMutation(() => syncDeviceTime(deviceId), {
    successMessage: 'Clock sync queued. Sent on the terminal’s next poll.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.commandLog(deviceId) }),
  });
  return (
    <Button
      type="button"
      variant="outline"
      size="sm"
      disabled={sync.isPending}
      onClick={() => sync.mutate()}
    >
      <Clock className="mr-1.5 size-4" aria-hidden />
      {sync.isPending ? 'Queueing…' : 'Sync time'}
    </Button>
  );
}

/**
 * Building-level "Sync names", preview first.
 *
 * <p>The preview is a separate server call that queues nothing, per the standing rule — and it
 * matters more here than for a roster preview, because the committed version writes to every screen
 * in the building. The count it reports is people MULTIPLIED BY terminals, which is not what an
 * operator expects until they see it.
 */
export function SyncNamesDialog({
  siteId,
  siteName,
  open,
  onOpenChange,
}: {
  siteId: string;
  siteName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const qc = useQueryClient();
  const [report, setReport] = React.useState<NameSyncReport | null>(null);
  const [done, setDone] = React.useState<NameSyncReport | null>(null);

  React.useEffect(() => {
    if (!open) {
      setReport(null);
      setDone(null);
    }
  }, [open]);

  const preview = useApiMutation(() => previewNameSync(siteId), {
    onSuccess: (r: NameSyncReport) => setReport(r),
  });
  const commit = useApiMutation(() => syncNames(siteId), {
    onSuccess: (r: NameSyncReport) => {
      setDone(r);
      qc.invalidateQueries({ queryKey: iclockKeys.root });
    },
  });

  React.useEffect(() => {
    if (open && !report && !preview.isPending) preview.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-lg">
        <DialogHeader>
          <DialogTitle>Sync names to terminals</DialogTitle>
          <DialogDescription>
            Pushes every roster name at {siteName} to its terminals — the pass that clears slug and
            register names from the device screens.
          </DialogDescription>
        </DialogHeader>

        {done ? (
          <div className="space-y-3">
            <div className="rounded-lg border border-border bg-card p-3 text-sm">
              <p>
                <strong className="tabular-nums">{done.commandsQueued}</strong> commands queued —{' '}
                {done.people} people across {done.devices} terminal{done.devices === 1 ? '' : 's'}.
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                Terminals take one command per poll, so a large building takes a few minutes to work
                through. Progress shows in each terminal’s command log.
              </p>
            </div>
            <div className="flex justify-end">
              <Button onClick={() => onOpenChange(false)}>Done</Button>
            </div>
          </div>
        ) : preview.isPending || !report ? (
          <LoadingSkeleton lines={4} />
        ) : (
          <div className="space-y-4">
            <div className="rounded-lg border border-border bg-muted/40 p-3 text-sm">
              <p>
                <strong className="tabular-nums">{report.commandsQueued}</strong> commands —{' '}
                {report.people} named {report.people === 1 ? 'person' : 'people'} ×{' '}
                {report.devices} terminal{report.devices === 1 ? '' : 's'}.
              </p>
              {report.skipped > 0 ? (
                <p className="mt-1 inline-flex items-center gap-1.5 text-xs text-warning">
                  <TriangleAlert className="size-3.5" aria-hidden />
                  {report.skipped} skipped — no name on the roster yet. Pushing an empty name would
                  blank the screen.
                </p>
              ) : null}
            </div>

            {report.rows.some((r) => r.detail) ? (
              <div className="max-h-48 overflow-auto rounded-lg border border-border">
                <ul className="divide-y divide-border text-xs">
                  {report.rows
                    .filter((r) => r.detail)
                    .map((r) => (
                      <li key={r.personId} className="px-3 py-1.5">
                        <span className="font-mono">{r.pin}</span>{' '}
                        <span className="font-medium">{r.name ?? '—'}</span>
                        <span className="ml-2 text-muted-foreground">{r.detail}</span>
                      </li>
                    ))}
                </ul>
              </div>
            ) : null}

            <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-4">
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                Cancel
              </Button>
              <Button
                onClick={() => commit.mutate()}
                disabled={commit.isPending || report.commandsQueued === 0}
              >
                {commit.isPending ? 'Queueing…' : `Send ${report.commandsQueued}`}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** The command log for one terminal, newest first. */
export function DeviceCommandLog({ deviceId }: { deviceId: string }) {
  const query = useApiQuery<DeviceCommand[]>(
    iclockKeys.commandLog(deviceId),
    (signal) => getCommandLog(deviceId, signal),
    { retry: false, refetchInterval: 15000 },
  );

  if (query.isLoading) return <LoadingSkeleton lines={4} />;
  const rows = query.data ?? [];
  if (rows.length === 0) {
    return (
      <p className="px-3 py-2 text-xs text-muted-foreground">
        Nothing sent to this terminal yet.
      </p>
    );
  }

  return (
    <ul className="divide-y divide-border text-xs">
      {rows.map((c) => (
        <li key={c.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
          <span className={cn('inline-flex items-center gap-1.5', STATUS_TONE[c.status])}>
            <StatusIcon status={c.status} />
            {c.status}
          </span>
          <Badge variant="outline" className="text-xs">{c.kind}</Badge>
          {c.pin ? <span className="font-mono text-muted-foreground">pin {c.pin}</span> : null}
          <span className="min-w-0 flex-1 truncate font-mono text-muted-foreground" title={c.payload}>
            {c.payload}
          </span>
          {c.serveCount > 1 ? (
            <span className="text-warning">served {c.serveCount}×</span>
          ) : null}
          {c.failureReason ? (
            <span className="text-destructive">{c.failureReason}</span>
          ) : null}
          <span className="text-muted-foreground" title={istDateTime(c.createdAt)}>
            {relativeTime(c.completedAt ?? c.sentAt ?? c.createdAt)}
          </span>
        </li>
      ))}
    </ul>
  );
}
