'use client';

import * as React from 'react';
import Link from 'next/link';
import { Coffee, DoorOpen, Moon, Users } from 'lucide-react';
import { getBoardScoped, iclockKeys, listSites, type Board, type PersonChip } from '@/lib/api/iclock';
import { useApiQuery } from '@/lib/api/hooks';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { GroupedList } from '@/components/console/grouped-list';
import { Combobox } from '@/components/console/combobox';
import { ViewToggle, usePersistedViewMode, type ViewMode } from '@/components/console/view-toggle';
import { BreakAlertStrip, BreakSettings } from './break-alert-strip';
import { MissingOutSection } from './missing-out-section';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from '@/components/ui/collapsible';
import { ChevronRight } from 'lucide-react';
import { istDayLabel, istTime } from '@/lib/date';
import type { GroupOptions } from '@/lib/console/grouping';
import { cn } from '@/lib/utils';
import { ConsoleError } from './console-error';

/** How often the board re-reads. 30s is the shortest interval that still feels live at a gate. */
const REFETCH_MS = 30_000;

/**
 * The live pulse.
 *
 * `stale` is driven by consecutive FAILURES, never by `isFetching`: a routine 30-second refetch is not
 * a reconnection, and flashing "Reconnecting…" twice a minute on a healthy board would train the
 * operator to ignore the one time it means something.
 */
function LiveIndicator({ asOf, stale }: { asOf: string; stale: boolean }) {
  return (
    <span className="inline-flex items-center gap-2 text-xs text-muted-foreground">
      <span className="relative flex size-2">
        {!stale ? (
          <span className="absolute inline-flex size-full animate-ping rounded-full bg-success opacity-60" />
        ) : null}
        <span
          className={cn('relative inline-flex size-2 rounded-full', stale ? 'bg-warning' : 'bg-success')}
        />
      </span>
      {stale ? `Reconnecting… showing ${istTime(asOf)}` : `Live · as of ${istTime(asOf)}`}
    </span>
  );
}

/** One person. Deliberately one line tall — the standard keeps rows scannable. */
function PersonRow({ person, siteId }: { person: PersonChip; siteId: string }) {
  return (
    <Link
      href={`/super-admin/time-attendance/people/${person.personId}?site=${siteId}`}
      className="flex items-center justify-between gap-3 px-3 py-2 transition-colors hover:bg-accent"
    >
      <span className="min-w-0 flex-1 truncate text-sm">
        {person.name ?? <span className="text-muted-foreground">Unnamed · {person.pin}</span>}
        {person.team ? (
          <span className="ml-2 text-xs text-muted-foreground">{person.team}</span>
        ) : null}
      </span>
      {person.lastAt ? (
        <span className="shrink-0 text-xs tabular-nums text-muted-foreground">
          {istTime(person.lastAt)}
        </span>
      ) : null}
    </Link>
  );
}

type Axis = 'company' | 'team' | 'building';

/**
 * The grouping axes the board offers, and the view mode, BOTH as parameters to the shared rule.
 *
 * In 'all' mode the axis is irrelevant — the function returns one flat newest-first list — but it is
 * still passed, so switching back to Grouped restores the operator's axis rather than resetting it.
 */
function groupingFor(axis: Axis, mode: ViewMode): GroupOptions<PersonChip> {
  const base = { timeOf: (p: PersonChip) => p.lastAt, mode };
  if (axis === 'team') {
    return { ...base, keyOf: (p: PersonChip) => p.team, ungroupedLabel: 'No team' };
  }
  if (axis === 'building') {
    // Only offered when more than one building exists — grouping 208 people under a single heading
    // called "Orion Towers" is a heading, not a grouping.
    return { ...base, keyOf: (p: PersonChip) => p.siteName ?? null, ungroupedLabel: 'No building' };
  }
  return { ...base, keyOf: (p: PersonChip) => p.companyName, ungroupedLabel: 'No company' };
}

function Column({
  title,
  icon: Icon,
  people,
  siteId,
  tone,
  axis,
  mode,
  emptyLine,
  storageKey,
  defaultOpen,
}: {
  title: string;
  icon: React.ComponentType<{ className?: string }>;
  people: PersonChip[];
  siteId: string;
  tone: 'success' | 'primarySoft' | 'neutral' | 'warning';
  axis: Axis;
  mode: ViewMode;
  emptyLine: string;
  storageKey: string;
  defaultOpen: boolean;
}) {
  const grouping = React.useMemo(() => groupingFor(axis, mode), [axis, mode]);
  // Column-level collapse, ORTHOGONAL to Grouped/All: one is "is this column worth screen space",
  // the other is "how are its rows arranged". Not-arrived starts collapsed because it is the largest
  // column and the least urgent — 139 people who have not shown up push the three columns that matter
  // off the screen. Presentation state only; the boolean is all that reaches the renderer.
  const [open, setOpen] = usePersistedColumn(`board.col.${storageKey}`, defaultOpen);

  return (
    <Collapsible open={open} onOpenChange={setOpen} asChild>
      <Card className={cn('flex flex-col p-4', open && 'min-h-[12rem]')}>
        <CollapsibleTrigger asChild>
          <button
            type="button"
            className={cn(
              'flex w-full items-center gap-2 rounded-md text-left transition-colors',
              'hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              open && 'mb-3',
            )}
          >
            <ChevronRight
              className={cn('size-4 shrink-0 text-muted-foreground transition-transform', open && 'rotate-90')}
              aria-hidden
            />
            <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
            <span className="min-w-0 flex-1 truncate text-sm font-semibold">{title}</span>
            <Badge variant={tone} className="tabular-nums">
              {people.length}
            </Badge>
          </button>
        </CollapsibleTrigger>
        <CollapsibleContent>
          {people.length === 0 ? (
            <p className="py-6 text-center text-sm text-muted-foreground">{emptyLine}</p>
          ) : (
            <GroupedList
              items={people}
              grouping={grouping}
              badgeVariant={tone}
              storageKey={`board.${storageKey}.${axis}`}
              itemKey={(p) => p.personId}
              renderItem={(p) => <PersonRow person={p} siteId={siteId} />}
            />
          )}
        </CollapsibleContent>
      </Card>
    </Collapsible>
  );
}

/**
 * Whether a board column starts open. Same guarded-storage discipline as the view toggle: a private
 * window or blocked site storage throws rather than returning empty, and losing the preference is free.
 */
function usePersistedColumn(key: string, fallback: boolean) {
  const [open, setOpen] = React.useState(fallback);
  React.useEffect(() => {
    try {
      const stored = window.localStorage.getItem(key);
      if (stored === 'open' || stored === 'closed') setOpen(stored === 'open');
    } catch {
      // Storage unavailable — the column still collapses, it just forgets between visits.
    }
  }, [key]);
  const update = React.useCallback(
    (next: boolean) => {
      setOpen(next);
      try {
        window.localStorage.setItem(key, next ? 'open' : 'closed');
      } catch {
        // As above.
      }
    },
    [key],
  );
  return [open, update] as const;
}

export function LiveBoard({ siteId }: { siteId: string }) {
  const [axis, setAxis] = React.useState<Axis>('company');
  const [mode, setMode] = usePersistedViewMode('board');
  // null = every building. Only meaningful once a second building exists, so the control hides at one.
  const [scope, setScope] = React.useState<string | null>(siteId);

  const sitesQuery = useApiQuery(iclockKeys.sites(), listSites, { retry: false });
  const buildings = sitesQuery.data ?? [];
  const multiBuilding = buildings.length > 1;

  const effectiveScope = multiBuilding ? scope : siteId;

  const query = useApiQuery<Board>(
    iclockKeys.boardScoped(effectiveScope),
    (signal) => getBoardScoped(effectiveScope, signal),
    { retry: false, refetchInterval: REFETCH_MS, placeholderData: (prev) => prev },
  );

  if (query.isLoading) return <LoadingSkeleton lines={10} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const b = query.data;
  const rosterSize = b.inOffice.length + b.inCafeteria.length + b.left.length + b.notArrived.length;
  const arrived = b.inOffice.length + b.inCafeteria.length + b.left.length;

  // EMPTY STATE — nobody on the roster.
  if (rosterSize === 0) {
    return (
      <EmptyState
        icon={Users}
        title="Nobody to show"
        description="The board draws from the roster. Import it and everyone enrolled on the terminals appears here."
      />
    );
  }

  const header = (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <span className="text-sm text-muted-foreground">Shift day {istDayLabel(b.shiftDate)}</span>
      <div className="flex flex-wrap items-center gap-3">
        <ViewToggle value={mode} onChange={setMode} />
        {multiBuilding ? (
          <Combobox
            ariaLabel="Building"
            options={buildings.map((b) => ({ value: b.id, label: b.name }))}
            value={scope}
            onChange={(v) => setScope(v || null)}
            emptyOptionLabel="All buildings"
            className="w-52"
          />
        ) : null}
        {/* The axis picker is meaningless in the flat ticker, so it goes away rather than sitting
            there inert — but the choice is remembered for when Grouped comes back. */}
        {mode === 'grouped' ? (
          <Combobox
            ariaLabel="Group people by"
            options={[
              { value: 'company', label: 'Group by company' },
              { value: 'team', label: 'Group by team' },
              ...(multiBuilding ? [{ value: 'building', label: 'Group by building' }] : []),
            ]}
            value={axis}
            onChange={(v) => setAxis(v as Axis)}
            className="w-48"
          />
        ) : null}
        {/* Reachable on a quiet night, not only during an incident. */}
        {effectiveScope ? <BreakSettings siteId={effectiveScope} /> : null}
        <LiveIndicator asOf={b.asOf} stale={query.failureCount > 0} />
      </div>
    </div>
  );

  // EMPTY STATE — the roster exists but the shift has not started: everyone is still NOT_ARRIVED.
  if (arrived === 0) {
    return (
      <div className="space-y-4">
        {header}
        <EmptyState
          icon={Moon}
          title="No one has arrived yet"
          description={`All ${rosterSize} people on the roster are still off site for this shift day. The board fills in as the first punches come through — it refreshes on its own.`}
        />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {header}
      <BreakAlertStrip alerts={b.exceedingBreak} siteId={effectiveScope} />
      <MissingOutSection
        rows={b.missingOut}
        shiftDate={b.missingOutShiftDate}
        siteId={effectiveScope}
      />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <Column
          title="In office"
          icon={Users}
          people={b.inOffice}
          siteId={siteId}
          tone="success"
          axis={axis}
          mode={mode}
          storageKey="inOffice"
          defaultOpen={true}
          emptyLine="Nobody in the office right now."
        />
        <Column
          title="Cafeteria"
          icon={Coffee}
          people={b.inCafeteria}
          siteId={siteId}
          tone="primarySoft"
          axis={axis}
          mode={mode}
          storageKey="inCafeteria"
          defaultOpen={true}
          emptyLine="Nobody in the cafeteria."
        />
        <Column
          title="Left"
          icon={DoorOpen}
          people={b.left}
          siteId={siteId}
          tone="neutral"
          axis={axis}
          mode={mode}
          storageKey="left"
          defaultOpen={true}
          emptyLine="Nobody has left yet."
        />
        {/* The 202 case. Grouped and collapsed, this is six headers rather than 202 cards — which is
            exactly the Gate A acceptance criterion, and why the grouping rule is a tested function. */}
        <Column
          title="Not arrived"
          icon={Moon}
          people={b.notArrived}
          siteId={siteId}
          tone="warning"
          axis={axis}
          mode={mode}
          storageKey="notArrived"
          defaultOpen={false}
          emptyLine="Everyone is accounted for."
        />
      </div>
    </div>
  );
}
