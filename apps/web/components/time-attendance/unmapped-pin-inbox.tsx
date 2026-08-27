'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Archive, Inbox, RefreshCw, UserPlus, UserRoundCheck } from 'lucide-react';
import {
  editPerson,
  getUnmappedInbox,
  iclockKeys,
  reresolve,
  type UnmappedPin,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { GroupedList } from '@/components/console/grouped-list';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { istDateTime, relativeTime } from '@/lib/date';
import { cn } from '@/lib/utils';
import { ReasonBadge, reasonMeta } from './person-state-badge';
import { ConsoleError } from './console-error';
import { PersonEditDialog } from './person-edit-dialog';

/** One inbox row, with the remedy its reason actually calls for. */
function InboxRow({ row, siteId }: { row: UnmappedPin; siteId: string }) {
  const qc = useQueryClient();
  const [adding, setAdding] = React.useState(false);
  const meta = reasonMeta(row.reason);

  const invalidate = () => qc.invalidateQueries({ queryKey: iclockKeys.site(siteId) });

  const reactivate = useApiMutation(
    () => editPerson(row.personId as string, { pin: row.pin, active: true }),
    { successMessage: 'Reactivated.', onSuccess: invalidate },
  );

  return (
    <div className="p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm font-semibold">Pin {row.pin}</span>
            <ReasonBadge reason={row.reason} />
          </div>
          <p className="mt-1 text-sm text-muted-foreground">{meta.hint}</p>
          <p className="mt-1.5 text-xs text-muted-foreground">
            <strong className="tabular-nums text-foreground">{row.livePunchCount}</strong> punches
            since the terminal was claimed
            {row.punchCount > row.livePunchCount ? (
              <> · {row.punchCount - row.livePunchCount} more in the archive</>
            ) : null}
            {row.lastSeen ? (
              <span title={istDateTime(row.lastSeen)}> · last seen {relativeTime(row.lastSeen)}</span>
            ) : null}
          </p>
          {row.suggestedName ? (
            <p className="mt-1.5 text-xs text-muted-foreground">
              The terminal has this pin enrolled as{' '}
              <span className="font-medium text-foreground">{row.suggestedName}</span> — a device
              register string, so confirm it before trusting it.
            </p>
          ) : null}
        </div>

        <div className="shrink-0">
          {row.inactivePerson && row.personId ? (
            <Button
              size="sm"
              variant="outline"
              onClick={() => reactivate.mutate()}
              disabled={reactivate.isPending}
            >
              <UserRoundCheck className="size-4" />
              Reactivate
            </Button>
          ) : row.reason === 'UNKNOWN_PIN' ? (
            <Button size="sm" variant="outline" onClick={() => setAdding(true)}>
              <UserPlus className="size-4" />
              Add person
            </Button>
          ) : null}
        </div>
      </div>

      {/* The SAME dialog the People screen uses. A new person is exactly where a free-text company
          field does the most damage — that row becomes the seed of a company nobody meant to create —
          so the inbox gets the closed company list and the team picker too. */}
      <PersonEditDialog
        siteId={siteId}
        person={null}
        presetPin={row.pin}
        open={adding}
        onOpenChange={setAdding}
        onSaved={invalidate}
      />
    </div>
  );
}

/**
 * The unmapped-pin inbox — pins that punched but resolve to nobody.
 *
 * Split by the live window, because with backfill OFF the two halves need opposite treatment: a pin
 * punching since the terminal was claimed is somebody standing at the gate right now going
 * unattributed, and an archive-only pin is history that no action will change. Listing both together
 * buries the handful that matter under hundreds of rows of archaeology, so the archive side is
 * deliberately a single count.
 */
export function UnmappedPinInbox({ siteId }: { siteId: string }) {
  const qc = useQueryClient();
  const query = useApiQuery(
    iclockKeys.inbox(siteId),
    (signal) => getUnmappedInbox(siteId, signal),
    { retry: false },
  );

  const invalidate = () => qc.invalidateQueries({ queryKey: iclockKeys.site(siteId) });

  const rerun = useApiMutation(() => reresolve(siteId), {
    successMessage: (r) => `Re-resolved ${r.scanned} punches — ${r.promoted} attributed.`,
    onSuccess: invalidate,
  });

  if (query.isLoading) return <LoadingSkeleton lines={6} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const { live, archiveOnlyPins, archiveOnlyPunches } = query.data;

  return (
    <Card className="p-6" id="inbox">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold">Unattributed pins</h2>
          <p className="text-sm text-muted-foreground">
            Punches that arrived but could not be matched to anyone. Retrying covers everything since
            the terminals were claimed — the archive before that is left alone.
          </p>
        </div>
        {/* One action, not two. A global sweep alongside a site-scoped re-resolve invited the operator
            to pick the wrong one on a screen that is entirely about a single site — and both now cover
            the same claim-bounded window anyway, so the global one only added a way to be confused. */}
        <Button
          variant="outline"
          size="sm"
          onClick={() => rerun.mutate()}
          disabled={rerun.isPending}
        >
          <RefreshCw className={cn('size-4', rerun.isPending && 'animate-spin')} />
          Retry attribution
        </Button>
      </div>

      {/* EMPTY STATE — inbox at zero. The good state, and it should read as one rather than as an
          absence: every pin that punched in the live window resolved to somebody. */}
      {live.length === 0 ? (
        <EmptyState
          icon={Inbox}
          title="Everything is attributed"
          description="Every pin that has punched since the terminals were claimed resolves to someone on the roster. Nothing to do here."
          className="py-10"
        />
      ) : (
        // Grouped by REASON, because the reason is the remedy: "no identity" is an add-person queue and
        // "marked inactive" is a reactivate queue, and working them together means switching task on
        // every row. Recency ordering comes from the shared rule via lastSeen — the pin punching now
        // leads, which is the whole point of the addendum on this screen.
        <GroupedList
          items={live}
          grouping={{
            keyOf: (r) => reasonMeta(r.reason).label,
            timeOf: (r) => r.lastSeen,
            ungroupedLabel: 'Unclassified',
          }}
          badgeVariant="warning"
          storageKey="inbox.reason"
          itemKey={(r) => `${r.pin}-${r.reason}`}
          renderItem={(r) => <InboxRow row={r} siteId={siteId} />}
        />
      )}

      {archiveOnlyPins > 0 ? (
        <div
          className={cn(
            surface('subtle'),
            'mt-4 flex items-start gap-3 px-4 py-3 text-sm text-muted-foreground',
          )}
        >
          <Archive className="mt-0.5 size-4 shrink-0" />
          <p>
            <strong className="tabular-nums text-foreground">{archiveOnlyPins}</strong> more pins
            appear only in history, covering{' '}
            <strong className="tabular-nums text-foreground">{archiveOnlyPunches}</strong> punches
            from before the terminals were claimed. Backfill is off, so these need no action — the raw
            punches are retained either way.
          </p>
        </div>
      ) : null}
    </Card>
  );
}
