'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CircleAlert, Trash2, TriangleAlert } from 'lucide-react';
import {
  iclockKeys,
  previewRemoveMany,
  previewRemoveOne,
  removeMany,
  removeOne,
  type RemovalReport,
} from '@/lib/api/iclock';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { istDateTime, relativeTime } from '@/lib/date';

/**
 * Taking somebody off the system — the terminals AND the roster, in one confirm.
 *
 * <p>Preview first, always, and the preview is a separate server call that writes nothing. What it
 * exists to show is the thing an operator cannot know from the row they clicked: whether this person
 * has attendance history (which decides delete-versus-deactivate), how many terminals will be
 * cleared, and — the one that stops mistakes — when they last came through a door.
 */
export function RemovePersonDialog({
  siteId,
  personIds,
  singlePersonId,
  open,
  onOpenChange,
  onDone,
}: {
  siteId: string;
  /** For the bulk path. */
  personIds?: string[];
  /** For the row menu. */
  singlePersonId?: string;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDone?: () => void;
}) {
  const qc = useQueryClient();
  const [report, setReport] = React.useState<RemovalReport | null>(null);
  const [done, setDone] = React.useState<RemovalReport | null>(null);

  React.useEffect(() => {
    if (!open) {
      setReport(null);
      setDone(null);
    }
  }, [open]);

  const preview = useApiMutation(
    () =>
      singlePersonId
        ? previewRemoveOne(singlePersonId)
        : previewRemoveMany(siteId, personIds ?? []),
    { onSuccess: (r: RemovalReport) => setReport(r) },
  );

  const commit = useApiMutation(
    () => (singlePersonId ? removeOne(singlePersonId) : removeMany(siteId, personIds ?? [])),
    {
      onSuccess: (r: RemovalReport) => {
        setDone(r);
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        onDone?.();
      },
    },
  );

  React.useEffect(() => {
    if (open && !report && !preview.isPending) preview.mutate();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const single = report?.rows.length === 1 ? report.rows[0] : null;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>
            {single ? `Remove ${single.name ?? single.pin}` : `Remove ${report?.people ?? ''} people`}
          </DialogTitle>
          <DialogDescription>
            Clears them from the terminals in their building and takes them off the roster. Their
            punches are kept — every one.
          </DialogDescription>
        </DialogHeader>

        {done ? (
          <div className="space-y-3 text-sm">
            <div className="rounded-lg border border-border bg-card p-3">
              <p>
                <strong className="tabular-nums">{done.commandsQueued}</strong> terminal deletion
                {done.commandsQueued === 1 ? '' : 's'} queued.{' '}
                {done.toDelete > 0 ? `${done.toDelete} removed from the roster. ` : ''}
                {done.toDeactivate > 0 ? `${done.toDeactivate} deactivated. ` : ''}
              </p>
              <p className="mt-1 text-xs text-muted-foreground">
                Terminals clear on their next poll — within about half a minute each.
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
                {single ? (
                  <>
                    Removes from <strong className="tabular-nums">{single.terminals}</strong>{' '}
                    terminal{single.terminals === 1 ? '' : 's'} and{' '}
                    {single.rosterOutcome === 'DELETE'
                      ? 'deletes the roster row'
                      : 'deactivates the roster row'}
                    {' '}— all punches kept.
                  </>
                ) : (
                  <>
                    <strong className="tabular-nums">{report.commandsQueued}</strong> terminal
                    deletions · {report.toDelete} roster {report.toDelete === 1 ? 'row' : 'rows'}{' '}
                    deleted · {report.toDeactivate} deactivated — all punches kept.
                  </>
                )}
              </p>
              {single && single.rosterOutcome === 'DEACTIVATE' ? (
                /* The anti-Bipul rule, surfaced before the operator commits rather than as an error
                   afterwards: history holds the row, so deactivation is what "removed" can mean. */
                <p className="mt-1.5 text-xs text-muted-foreground">{single.rosterReason}</p>
              ) : null}
            </div>

            {report.recentlyActive > 0 ? (
              <div className="rounded-lg border border-warning/30 bg-warning/5 p-3">
                <p className="inline-flex items-center gap-1.5 text-sm text-warning">
                  <CircleAlert className="size-4" aria-hidden />
                  <strong className="tabular-nums">{report.recentlyActive}</strong> of these punched
                  in the last 7 days
                </p>
                <p className="mt-1 text-xs text-muted-foreground">
                  Somebody still coming to work is usually not who you meant to remove.
                </p>
              </div>
            ) : null}

            {report.rows.length > 1 ? (
              <div className="max-h-64 overflow-auto rounded-lg border border-border">
                <ul className="divide-y divide-border text-xs">
                  {report.rows.map((r) => (
                    <li key={r.personId} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2">
                      <span className="font-mono">{r.pin}</span>
                      <span className="font-medium">{r.name ?? '—'}</span>
                      <span className="text-muted-foreground">
                        {r.rosterOutcome === 'DELETE' ? 'delete' : 'deactivate'}
                      </span>
                      <div className="flex-1" />
                      {r.lastPunchAt ? (
                        <span
                          className={r.recentlyActive ? 'text-warning' : 'text-muted-foreground'}
                          title={istDateTime(r.lastPunchAt)}
                        >
                          last punch {relativeTime(r.lastPunchAt)}
                        </span>
                      ) : (
                        <span className="text-muted-foreground">never punched</span>
                      )}
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
                variant="destructive"
                onClick={() => commit.mutate()}
                disabled={commit.isPending || report.people === 0}
              >
                {report.recentlyActive > 0 ? (
                  <TriangleAlert className="mr-1.5 size-4" aria-hidden />
                ) : (
                  <Trash2 className="mr-1.5 size-4" aria-hidden />
                )}
                {commit.isPending ? 'Removing…' : `Remove ${report.people}`}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
