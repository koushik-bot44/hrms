'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import {
  ChevronDown,
  ChevronRight,
  Coffee,
  DoorOpen,
  Settings2,
  TriangleAlert,
  Undo2,
  X,
} from 'lucide-react';
import {
  getSitePolicy,
  iclockKeys,
  saveSitePolicy,
  type BreakAlert,
  type SitePolicy,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import {
  dismissalKey,
  partitionAlerts,
  pruneDismissals,
  withDismissal,
  withoutDismissal,
} from '@/lib/console/alert-dismissal';
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
  const [dismissed, setDismissed] = useDismissedAlerts(alerts);
  const [collapsed, setCollapsed] = React.useState(false);
  const [showDismissed, setShowDismissed] = React.useState(false);

  // The layout DECISION lives in the pure function; this component only renders what it returns.
  const { active, dismissed: quietened } = partitionAlerts(alerts, dismissed);

  if (alerts.length === 0) return null;

  return (
    <div className="rounded-xl border border-warning/25 bg-warning/10 px-4 py-3">
      <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
        <button
          type="button"
          onClick={() => setCollapsed((c) => !c)}
          aria-expanded={!collapsed}
          className={cn(
            'inline-flex items-center gap-2 rounded-md text-sm font-semibold text-warning',
            'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
          )}
        >
          {collapsed ? (
            <ChevronRight className="size-4" aria-hidden />
          ) : (
            <ChevronDown className="size-4" aria-hidden />
          )}
          <TriangleAlert className="size-4" aria-hidden />
          Exceeding break ({active.length})
          {quietened.length > 0 ? (
            <span className="font-normal text-muted-foreground">
              · {quietened.length} dismissed
            </span>
          ) : null}
        </button>
        {siteId ? <BreakSettings siteId={siteId} /> : null}
      </div>

      {collapsed ? null : (
        <>
          {active.length === 0 ? (
            <p className="text-xs text-muted-foreground">
              All {quietened.length} dismissed. Anyone who steps out again will reappear here.
            </p>
          ) : (
            <ul className="flex flex-wrap gap-2">
              {active.map((a) => (
                <li key={dismissalKey(a)}>
                  <AlertChip
                    alert={a}
                    siteId={siteId}
                    onDismiss={() => setDismissed(withDismissal(dismissed, a))}
                  />
                </li>
              ))}
            </ul>
          )}

          {quietened.length > 0 ? (
            <div className="mt-2 border-t border-warning/20 pt-2">
              <button
                type="button"
                onClick={() => setShowDismissed((s) => !s)}
                aria-expanded={showDismissed}
                className={cn(
                  'inline-flex items-center gap-1.5 rounded-md text-xs text-muted-foreground',
                  'transition-colors hover:text-foreground',
                  'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
                )}
              >
                {showDismissed ? (
                  <ChevronDown className="size-3.5" aria-hidden />
                ) : (
                  <ChevronRight className="size-3.5" aria-hidden />
                )}
                Dismissed ({quietened.length})
              </button>
              {showDismissed ? (
                <ul className="mt-2 flex flex-wrap gap-2">
                  {quietened.map((a) => (
                    <li key={dismissalKey(a)}>
                      <AlertChip
                        alert={a}
                        siteId={siteId}
                        muted
                        onRestore={() => setDismissed(withoutDismissal(dismissed, a))}
                      />
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          ) : null}
        </>
      )}
    </div>
  );
}

/**
 * One person on the strip.
 *
 * The dismiss control is a sibling of the link rather than nested inside it: a button inside an
 * anchor is invalid markup, and in practice the click lands on whichever the browser feels like,
 * so an operator trying to clear a row would sometimes navigate away instead.
 */
function AlertChip({
  alert: a,
  siteId,
  muted = false,
  onDismiss,
  onRestore,
}: {
  alert: BreakAlert;
  siteId: string | null;
  muted?: boolean;
  onDismiss?: () => void;
  onRestore?: () => void;
}) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded-lg border border-warning/25 bg-card',
        muted && 'opacity-60',
      )}
    >
      <Link
        href={`/super-admin/time-attendance/people/${a.personId}${siteId ? `?site=${siteId}` : ''}`}
        className="inline-flex items-center gap-2 rounded-l-lg px-2.5 py-1.5 text-xs transition-colors hover:bg-accent"
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
      <button
        type="button"
        onClick={onDismiss ?? onRestore}
        aria-label={
          onDismiss
            ? `Dismiss ${a.name ?? 'this alert'}`
            : `Bring back ${a.name ?? 'this alert'}`
        }
        title={onDismiss ? 'Dismiss until they step out again' : 'Bring back'}
        className={cn(
          'inline-flex h-full items-center rounded-r-lg border-l border-warning/20 px-2 py-1.5',
          'text-muted-foreground transition-colors hover:bg-accent hover:text-foreground',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        )}
      >
        {onDismiss ? <X className="size-3.5" aria-hidden /> : <Undo2 className="size-3.5" aria-hidden />}
      </button>
    </span>
  );
}

/**
 * Dismissals for this browser, keyed by absence.
 *
 * Deliberately local rather than server state: acknowledging an alert is one operator saying "I know
 * about this one", not a fact about the person's attendance. Writing it to the roster would put a
 * console convenience into the attendance record, and every other viewer would inherit one person's
 * triage. The cost is that it does not follow the operator between machines, which is the right
 * trade for something that resets itself the moment anybody moves.
 *
 * Pruned against the live alerts on every refresh so the list cannot grow across shifts.
 */
function useDismissedAlerts(alerts: BreakAlert[]) {
  const [keys, setKeys] = React.useState<string[]>([]);

  React.useEffect(() => {
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      if (stored) setKeys(JSON.parse(stored) as string[]);
    } catch {
      // Storage unavailable or corrupt — the strip still works, it just forgets dismissals.
    }
  }, []);

  // Prune whenever the board refreshes. Safe because a key encodes the absence: once an alert is
  // gone — they came back, or it passed the cap — that key can never match again.
  React.useEffect(() => {
    setKeys((current) => {
      const pruned = pruneDismissals(current, alerts);
      if (pruned.length === current.length) return current;
      try {
        window.localStorage.setItem(STORAGE_KEY, JSON.stringify(pruned));
      } catch {
        // As above.
      }
      return pruned;
    });
  }, [alerts]);

  const update = React.useCallback((next: string[]) => {
    setKeys(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
    } catch {
      // As above.
    }
  }, []);

  return [keys, update] as const;
}

const STORAGE_KEY = 'console.breakAlerts.dismissed';

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
