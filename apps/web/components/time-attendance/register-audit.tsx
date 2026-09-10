'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CircleAlert, Fingerprint, ScanFace, ScanSearch, TriangleAlert } from 'lucide-react';
import {
  deleteFromRegister,
  getRegisterAudit,
  iclockKeys,
  requestRegisterAudit,
  type AuditResult,
  type AuditRow,
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
import { relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';

/**
 * What a terminal is actually holding, against the roster.
 *
 * <p>Four findings, all keyed on pin. There is deliberately no NAME DRIFT group: this firmware never
 * sends a name, so there is nothing to compare a roster name against. Correcting a name is still a
 * per-person action from their own row — it just cannot be something an audit discovers.
 */

const VERDICT_TONE: Record<string, string> = {
  CLEAN: 'text-muted-foreground',
  STALE: 'text-destructive',
  UNKNOWN: 'text-warning',
};

const VERDICT_WORD: Record<string, string> = {
  CLEAN: 'on the roster',
  STALE: 'left — still on the door',
  UNKNOWN: 'never rostered here',
};

function Inventory({ row }: { row: AuditRow }) {
  if (row.fingerCount === 0 && !row.hasFace) {
    return <span className="text-warning">nothing enrolled</span>;
  }
  return (
    <span className="inline-flex items-center gap-2 text-muted-foreground">
      {row.hasFace ? (
        <span className="inline-flex items-center gap-1">
          <ScanFace className="size-3.5" aria-hidden />
          Face
        </span>
      ) : null}
      {row.fingerCount > 0 ? (
        <span className="inline-flex items-center gap-1 tabular-nums">
          <Fingerprint className="size-3.5" aria-hidden />
          {row.fingerCount}
          {row.fingerIndexes ? (
            <span className="text-xs">({row.fingerIndexes})</span>
          ) : null}
        </span>
      ) : null}
    </span>
  );
}

export function RegisterAuditDialog({
  deviceId,
  deviceName,
  open,
  onOpenChange,
}: {
  deviceId: string;
  deviceName: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const qc = useQueryClient();
  const [picked, setPicked] = React.useState<Set<string>>(new Set());

  const query = useApiQuery<AuditResult | null>(
    iclockKeys.registerAudit(deviceId),
    (signal) => getRegisterAudit(deviceId, signal),
    { retry: false, refetchInterval: open ? 20000 : false },
  );
  const audit = query.data ?? null;

  const ask = useApiMutation(() => requestRegisterAudit(deviceId), {
    successMessage:
      'Asked the terminal for its register. It answers over a few minutes — this refreshes itself.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.registerAudit(deviceId) }),
  });

  const remove = useApiMutation(() => deleteFromRegister(deviceId, [...picked]), {
    successMessage: (r: { commandsQueued: number }) =>
      `Removing ${r.commandsQueued} from this terminal.`,
    onSuccess: () => {
      setPicked(new Set());
      qc.invalidateQueries({ queryKey: iclockKeys.root });
    },
  });

  // STALE and UNKNOWN are pre-checked; CLEAN never is. The whole point of the review is that the
  // dangerous selection — somebody who still works here — takes a deliberate click to make.
  React.useEffect(() => {
    if (!audit) return;
    setPicked(
      new Set(audit.rows.filter((r) => r.verdict !== 'CLEAN').map((r) => r.pin)),
    );
  }, [audit?.auditId, audit?.rows.length]); // eslint-disable-line react-hooks/exhaustive-deps

  const toggle = (pin: string) =>
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(pin)) next.delete(pin);
      else next.add(pin);
      return next;
    });

  const actionable = (audit?.rows ?? []).filter((r) => r.verdict !== 'CLEAN');
  const cleanCount = audit?.clean ?? 0;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Audit register — {deviceName}</DialogTitle>
          <DialogDescription>
            What this terminal is actually holding, against {`this building's`} roster. Registers
            only: nothing here touches a roster row or a punch.
          </DialogDescription>
        </DialogHeader>

        {query.isLoading ? (
          <LoadingSkeleton lines={5} />
        ) : !audit ? (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              This terminal has never been audited. Asking it makes it send its whole register —
              several megabytes over a few minutes — so it is worth doing outside busy hours.
            </p>
            <div className="flex justify-end">
              <Button onClick={() => ask.mutate()} disabled={ask.isPending}>
                <ScanSearch className="mr-1.5 size-4" aria-hidden />
                {ask.isPending ? 'Asking…' : 'Fetch register'}
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border border-border bg-muted/40 p-3 text-sm">
              <span className="tabular-nums">
                <strong>{audit.pinsOnDevice}</strong> on the terminal
              </span>
              <span className="tabular-nums text-muted-foreground">{cleanCount} clean</span>
              {audit.stale > 0 ? (
                <span className="tabular-nums text-destructive">{audit.stale} left</span>
              ) : null}
              {audit.unknown > 0 ? (
                <span className="tabular-nums text-warning">{audit.unknown} never rostered</span>
              ) : null}
              <div className="flex-1" />
              <span className="text-xs text-muted-foreground">
                {audit.status === 'REQUESTED' ? 'still arriving…' : relativeTime(audit.requestedAt)}
              </span>
            </div>

            {audit.gaps.length > 0 ? (
              /* The mirror image, and the finding operators act on fastest: somebody who IS on the
                 roster and holds nothing here. They cannot open this door and find out at the door. */
              <div className="rounded-lg border border-warning/30 bg-warning/5 p-3">
                <p className="inline-flex items-center gap-1.5 text-sm text-warning">
                  <CircleAlert className="size-4" aria-hidden />
                  <strong className="tabular-nums">{audit.gaps.length}</strong> on the roster with
                  nothing enrolled on this terminal
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  They cannot open this door. Enrol them from their row on the People screen.
                </p>
                <ul className="mt-2 flex flex-wrap gap-x-3 gap-y-1 text-xs">
                  {audit.gaps.slice(0, 24).map((g) => (
                    <li key={g.personId} className="text-muted-foreground">
                      <span className="font-mono">{g.pin}</span> {g.name ?? '—'}
                    </li>
                  ))}
                  {audit.gaps.length > 24 ? (
                    <li className="text-muted-foreground">+{audit.gaps.length - 24} more</li>
                  ) : null}
                </ul>
              </div>
            ) : null}

            {actionable.length > 0 ? (
              <div className="max-h-72 overflow-auto rounded-lg border border-border">
                <ul className="divide-y divide-border text-sm">
                  {actionable.map((r) => (
                    <li key={r.pin} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
                      <input
                        type="checkbox"
                        className="size-4 accent-primary"
                        checked={picked.has(r.pin)}
                        onChange={() => toggle(r.pin)}
                      />
                      <span className="font-mono">{r.pin}</span>
                      <span className="font-medium">{r.personName ?? '—'}</span>
                      <span className={cn('text-xs', VERDICT_TONE[r.verdict])}>
                        {VERDICT_WORD[r.verdict] ?? r.verdict}
                      </span>
                      <div className="flex-1" />
                      <Inventory row={r} />
                    </li>
                  ))}
                </ul>
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">
                Nothing to remove — every pin on this terminal belongs to somebody who works here.
              </p>
            )}

            {cleanCount > 0 ? (
              <p className="text-xs text-muted-foreground">
                {cleanCount} clean {cleanCount === 1 ? 'entry is' : 'entries are'} not listed. They
                are on the roster and belong here.
              </p>
            ) : null}

            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-border pt-4">
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={() => ask.mutate()}
                disabled={ask.isPending}
              >
                <ScanSearch className="mr-1.5 size-4" aria-hidden />
                Re-fetch
              </Button>
              <div className="flex-1" />
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                Close
              </Button>
              <Button
                variant="destructive"
                disabled={picked.size === 0 || remove.isPending}
                onClick={() => remove.mutate()}
              >
                <TriangleAlert className="mr-1.5 size-4" aria-hidden />
                {remove.isPending
                  ? 'Removing…'
                  : `Delete ${picked.size} from this terminal`}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

/** "Last audited" for the devices list. */
export function LastAuditedLabel({ deviceId }: { deviceId: string }) {
  const query = useApiQuery<AuditResult | null>(
    iclockKeys.registerAudit(deviceId),
    (signal) => getRegisterAudit(deviceId, signal),
    { retry: false },
  );
  if (!query.data) return null;
  return (
    <span className="text-xs text-muted-foreground" title="Register last compared to the roster">
      audited {relativeTime(query.data.requestedAt)}
    </span>
  );
}
