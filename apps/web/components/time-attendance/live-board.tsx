'use client';

import * as React from 'react';
import Link from 'next/link';
import { Coffee, DoorOpen, Moon, Users } from 'lucide-react';
import { getBoard, iclockKeys, type Board, type PersonChip } from '@/lib/api/iclock';
import { useApiQuery } from '@/lib/api/hooks';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { GroupedList } from '@/components/console/grouped-list';
import { Combobox } from '@/components/console/combobox';
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

type Axis = 'company' | 'team';

/** The grouping axes the board offers. Company is the default the standard names. */
function groupingFor(axis: Axis): GroupOptions<PersonChip> {
  if (axis === 'team') {
    return {
      keyOf: (p) => p.team,
      timeOf: (p) => p.lastAt,
      ungroupedLabel: 'No team',
    };
  }
  return {
    keyOf: (p) => p.companyName,
    timeOf: (p) => p.lastAt,
    ungroupedLabel: 'No company',
  };
}

function Column({
  title,
  icon: Icon,
  people,
  siteId,
  tone,
  axis,
  emptyLine,
  storageKey,
}: {
  title: string;
  icon: React.ComponentType<{ className?: string }>;
  people: PersonChip[];
  siteId: string;
  tone: 'success' | 'primarySoft' | 'neutral' | 'warning';
  axis: Axis;
  emptyLine: string;
  storageKey: string;
}) {
  const grouping = React.useMemo(() => groupingFor(axis), [axis]);
  return (
    <Card className="flex min-h-[12rem] flex-col p-4">
      <div className="mb-3 flex items-center justify-between gap-2">
        <span className="inline-flex items-center gap-2 text-sm font-semibold">
          <Icon className="size-4 text-muted-foreground" />
          {title}
        </span>
        <Badge variant={tone} className="tabular-nums">
          {people.length}
        </Badge>
      </div>
      {people.length === 0 ? (
        <p className="my-auto py-6 text-center text-sm text-muted-foreground">{emptyLine}</p>
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
    </Card>
  );
}

export function LiveBoard({ siteId }: { siteId: string }) {
  const [axis, setAxis] = React.useState<Axis>('company');

  const query = useApiQuery<Board>(
    iclockKeys.board(siteId),
    (signal) => getBoard(siteId, signal),
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
      <div className="flex items-center gap-3">
        <Combobox
          ariaLabel="Group people by"
          options={[
            { value: 'company', label: 'Group by company' },
            { value: 'team', label: 'Group by team' },
          ]}
          value={axis}
          onChange={(v) => setAxis(v as Axis)}
          className="w-48"
        />
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
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <Column
          title="In office"
          icon={Users}
          people={b.inOffice}
          siteId={siteId}
          tone="success"
          axis={axis}
          storageKey="inOffice"
          emptyLine="Nobody in the office right now."
        />
        <Column
          title="Cafeteria"
          icon={Coffee}
          people={b.inCafeteria}
          siteId={siteId}
          tone="primarySoft"
          axis={axis}
          storageKey="inCafeteria"
          emptyLine="Nobody in the cafeteria."
        />
        <Column
          title="Left"
          icon={DoorOpen}
          people={b.left}
          siteId={siteId}
          tone="neutral"
          axis={axis}
          storageKey="left"
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
          storageKey="notArrived"
          emptyLine="Everyone is accounted for."
        />
      </div>
    </div>
  );
}
