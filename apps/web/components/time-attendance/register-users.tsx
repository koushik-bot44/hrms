'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Fingerprint, RefreshCw, ScanFace, Search, Trash2 } from 'lucide-react';
import {
  deleteFromRegister,
  getRegisterUsers,
  iclockKeys,
  requestRegisterAudit,
  type RegisterListing,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { relativeTime } from '@/lib/date';

/**
 * What is on this terminal, as a list.
 *
 * <p>The plain inventory, deliberately opinion-free. The register audit exists to compare a terminal
 * against the roster and group the differences, which is the right tool when the question is what is
 * WRONG. This answers the other question — what is actually on this machine — and answering that
 * with verdicts and amber boxes makes somebody read a diff when they wanted a list.
 *
 * <p>The one piece of roster knowledge that appears here is inline on a row whose deletion the server
 * will refuse. That is not a verdict; it is the reason a checkbox will not do what it looks like it
 * does, said before the operator ticks it rather than after.
 */
export function RegisterUsersDialog({
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
  const [term, setTerm] = React.useState('');

  const query = useApiQuery<RegisterListing>(
    iclockKeys.registerUsers(deviceId),
    (signal) => getRegisterUsers(deviceId, signal),
    { retry: false, refetchInterval: open ? 20000 : false },
  );

  const refresh = useApiMutation(() => requestRegisterAudit(deviceId), {
    successMessage: 'Asked the terminal for its users. The list fills in as it answers.',
    onSuccess: () => qc.invalidateQueries({ queryKey: iclockKeys.registerUsers(deviceId) }),
  });

  const remove = useApiMutation(() => deleteFromRegister(deviceId, [...picked]), {
    successMessage: (r: { commandsQueued: number; refused: string[] }) =>
      r.refused.length > 0
        ? `Removing ${r.commandsQueued}. ${r.refused.length} skipped — still on the roster here.`
        : `Removing ${r.commandsQueued} from this terminal.`,
    onSuccess: () => {
      setPicked(new Set());
      qc.invalidateQueries({ queryKey: iclockKeys.root });
    },
  });

  React.useEffect(() => {
    if (!open) {
      setPicked(new Set());
      setTerm('');
    }
  }, [open]);

  const listing = query.data;
  const needle = term.trim().toLowerCase();
  const rows = (listing?.rows ?? []).filter(
    (r) =>
      !needle ||
      r.pin.includes(needle) ||
      (r.deviceName ?? '').toLowerCase().includes(needle),
  );

  const toggle = (pin: string) =>
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(pin)) next.delete(pin);
      else next.add(pin);
      return next;
    });

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Users on {deviceName}</DialogTitle>
          <DialogDescription>
            Exactly what the terminal reported holding. Registers only — nothing here touches a
            roster row or a punch.
          </DialogDescription>
        </DialogHeader>

        {query.isLoading ? (
          <LoadingSkeleton lines={6} />
        ) : !listing || listing.users === 0 ? (
          <div className="space-y-3">
            <p className="text-sm text-muted-foreground">
              Nothing fetched from this terminal yet. Asking makes it send its user list, which takes
              a few minutes and several megabytes — worth doing outside busy hours.
            </p>
            <div className="flex justify-end">
              <Button onClick={() => refresh.mutate()} disabled={refresh.isPending}>
                <RefreshCw className="mr-1.5 size-4" aria-hidden />
                {refresh.isPending ? 'Asking…' : 'Fetch users'}
              </Button>
            </div>
          </div>
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <span className="text-sm tabular-nums">
                <strong>{listing.users}</strong> user{listing.users === 1 ? '' : 's'}
              </span>
              <span className="text-xs text-muted-foreground">
                {listing.fetchedAt ? `fetched ${relativeTime(listing.fetchedAt)}` : 'not fetched yet'}
              </span>
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => refresh.mutate()}
                disabled={refresh.isPending}
              >
                <RefreshCw className="mr-1.5 size-3.5" aria-hidden />
                Refresh
              </Button>
              <div className="relative ml-auto">
                <Search
                  className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-muted-foreground"
                  aria-hidden
                />
                <Input
                  value={term}
                  onChange={(e) => setTerm(e.target.value)}
                  placeholder="Search pin or name…"
                  className="h-8 w-52 pl-8 text-sm"
                />
              </div>
            </div>

            <div className="max-h-[26rem] overflow-auto rounded-lg border border-border">
              <ul className="divide-y divide-border text-sm">
                {rows.map((r) => (
                  <li
                    key={r.pin}
                    className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2"
                  >
                    <input
                      type="checkbox"
                      className="size-4 accent-primary"
                      checked={picked.has(r.pin)}
                      onChange={() => toggle(r.pin)}
                    />
                    <span className="w-20 font-mono tabular-nums">{r.pin}</span>
                    <span className="min-w-0 flex-1 truncate">
                      {r.deviceName ?? <span className="text-muted-foreground">—</span>}
                    </span>
                    {r.hasFace ? (
                      <ScanFace className="size-4 text-muted-foreground" aria-label="Face" />
                    ) : null}
                    {r.fingerCount > 0 ? (
                      <span
                        className="inline-flex items-center gap-1 tabular-nums text-muted-foreground"
                        title={r.fingerIndexes ? `Fingers ${r.fingerIndexes}` : undefined}
                      >
                        <Fingerprint className="size-4" aria-hidden />
                        {r.fingerCount}
                      </span>
                    ) : null}
                    {r.onActiveRoster ? (
                      <span className="text-xs text-muted-foreground">
                        on roster — remove there first
                      </span>
                    ) : null}
                  </li>
                ))}
                {rows.length === 0 ? (
                  <li className="px-3 py-6 text-center text-sm text-muted-foreground">
                    Nothing matches “{term}”.
                  </li>
                ) : null}
              </ul>
            </div>

            <div className="flex flex-wrap items-center justify-end gap-2 border-t border-border pt-3">
              <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
                Close
              </Button>
              <Button
                variant="destructive"
                disabled={picked.size === 0 || remove.isPending}
                onClick={() => remove.mutate()}
              >
                <Trash2 className="mr-1.5 size-4" aria-hidden />
                {remove.isPending ? 'Removing…' : `Delete ${picked.size} from this terminal`}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
