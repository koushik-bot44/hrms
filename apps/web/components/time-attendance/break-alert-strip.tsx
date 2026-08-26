'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { Coffee, DoorOpen, Settings2, TriangleAlert } from 'lucide-react';
import {
  getSitePolicy,
  iclockKeys,
  saveSitePolicy,
  type BreakAlert,
  type SitePolicy,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover';
import { istTime } from '@/lib/date';
import { cn } from '@/lib/utils';

/**
 * "Exceeding break" — an amber strip above the board's columns.
 *
 * An OVERLAY, not a fifth column. Everyone here also appears in their normal presence column with a
 * matching badge, because someone who has stepped out is still findable where the operator expects
 * them; moving them into an alert bucket would make the board worse at its main job.
 *
 * Sorted elapsed-descending by the server — a documented exception to the recency standard. These rank
 * by how overdue someone is, and newest-first would put the person who just stepped out at the top of
 * an alert list, which inverts the only thing the list is for.
 */
export function BreakAlertStrip({
  alerts,
  siteId,
}: {
  alerts: BreakAlert[];
  siteId: string | null;
}) {
  if (alerts.length === 0) return null;

  return (
    <div className="rounded-xl border border-warning/25 bg-warning/10 px-4 py-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <span className="inline-flex items-center gap-2 text-sm font-semibold text-warning">
          <TriangleAlert className="size-4" aria-hidden />
          Exceeding break ({alerts.length})
        </span>
        {siteId ? <BreakSettings siteId={siteId} /> : null}
      </div>
      <ul className="flex flex-wrap gap-2">
        {alerts.map((a) => (
          <li key={a.personId}>
            <Link
              href={`/super-admin/time-attendance/people/${a.personId}${siteId ? `?site=${siteId}` : ''}`}
              className={cn(
                'inline-flex items-center gap-2 rounded-lg border border-warning/25 bg-card',
                'px-2.5 py-1.5 text-xs transition-colors hover:bg-accent',
              )}
            >
              {a.where === 'CAFETERIA' ? (
                <Coffee className="size-3.5 shrink-0 text-warning" aria-hidden />
              ) : (
                <DoorOpen className="size-3.5 shrink-0 text-warning" aria-hidden />
              )}
              <span className="font-medium">{a.name ?? 'Unnamed'}</span>
              <span className="text-muted-foreground">
                {a.where === 'CAFETERIA' ? 'Cafeteria' : 'Outside'}
              </span>
              <span className="font-semibold tabular-nums text-warning">
                {a.where === 'CAFETERIA' ? 'break' : 'out'} {a.elapsedMinutes}m
              </span>
              <span className="text-muted-foreground">since {istTime(a.since)}</span>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * The thresholds, editable in place.
 *
 * Deliberately reachable from BOTH the strip header and the board toolbar. If it only appeared on the
 * strip it would be discoverable exclusively during an incident — precisely when nobody wants to go
 * hunting for a settings control — and invisible on the quiet night when someone actually wants to
 * tune it.
 */
export function BreakSettings({ siteId, className }: { siteId: string; className?: string }) {
  const qc = useQueryClient();
  const [open, setOpen] = React.useState(false);

  const query = useApiQuery<SitePolicy>(
    iclockKeys.policy(siteId),
    (signal) => getSitePolicy(siteId, signal),
    { retry: false },
  );

  const [min, setMin] = React.useState('');
  const [max, setMax] = React.useState('');

  // Seed the fields from the server whenever the popover opens, so a cancelled edit does not linger.
  React.useEffect(() => {
    if (open && query.data) {
      setMin(String(query.data.breakAlertMin));
      setMax(String(query.data.breakAlertMaxMin));
    }
  }, [open, query.data]);

  const save = useApiMutation(
    () =>
      saveSitePolicy(siteId, {
        breakAlertMin: Number(min),
        breakAlertMaxMin: Number(max),
      }),
    {
      successMessage: 'Saved — takes effect on the next refresh.',
      onSuccess: () => {
        // The board reads these on every refresh, so invalidating the site refreshes the alerts too.
        qc.invalidateQueries({ queryKey: iclockKeys.root });
        setOpen(false);
      },
    },
  );

  const minNum = Number(min);
  const maxNum = Number(max);
  const invalid =
    !Number.isInteger(minNum) || !Number.isInteger(maxNum) || minNum < 5 || maxNum <= minNum;

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Break alert thresholds"
          className={cn(
            'inline-flex h-8 items-center gap-1.5 rounded-full border border-border bg-card px-2.5',
            'text-xs text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            className,
          )}
        >
          <Settings2 className="size-3.5" aria-hidden />
          {query.data ? `${query.data.breakAlertMin}m` : '…'}
        </button>
      </PopoverTrigger>
      <PopoverContent className="w-80 p-4">
        <form
          className="space-y-4"
          onSubmit={(e) => {
            e.preventDefault();
            if (!invalid) save.mutate();
          }}
        >
          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="break-min">
              Alert after
            </label>
            <div className="flex items-center gap-2">
              <Input
                id="break-min"
                type="number"
                min={5}
                value={min}
                onChange={(e) => setMin(e.target.value)}
                className="w-24"
              />
              <span className="text-sm text-muted-foreground">minutes</span>
            </div>
            <p className="text-xs text-muted-foreground">
              Someone away longer than this shows here.
            </p>
          </div>

          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="break-max">
              Stop treating as break after
            </label>
            <div className="flex items-center gap-2">
              <Input
                id="break-max"
                type="number"
                min={6}
                value={max}
                onChange={(e) => setMax(e.target.value)}
                className="w-24"
              />
              <span className="text-sm text-muted-foreground">minutes</span>
            </div>
            <p className="text-xs text-muted-foreground">
              Away longer than this counts as left for the day.
            </p>
          </div>

          {invalid && min && max ? (
            <p className="text-xs text-destructive">
              {minNum < 5
                ? 'Alert after must be at least 5 minutes.'
                : 'The second number must be larger than the first, or every alert retires as soon as it appears.'}
            </p>
          ) : null}

          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" size="sm" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" size="sm" disabled={invalid || save.isPending}>
              {save.isPending ? 'Saving…' : 'Save'}
            </Button>
          </div>
        </form>
      </PopoverContent>
    </Popover>
  );
}
