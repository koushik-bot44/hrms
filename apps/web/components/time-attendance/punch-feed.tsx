'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { AlertCircle, Layers, Radio, UserPlus } from 'lucide-react';
import { VerifyModeBadge } from './verify-mode';
import {
  getPunchFeed,
  iclockKeys,
  listDevices,
  listSites,
  upsertPerson,
  type Attribution,
  type Feed,
  type FeedRow,
} from '@/lib/api/iclock';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { Combobox } from '@/components/console/combobox';
import { istDayLabel, istTime, istTodayIso } from '@/lib/date';
import { cn } from '@/lib/utils';
import { DeviceRoleBadge } from './device-role';
import { ConsoleError } from './console-error';

const REFETCH_MS = 15_000;

/**
 * The punch feed: what the terminals actually sent, newest first.
 *
 * <p>INHERENTLY A TICKER, so it has no grouped mode. The board answers "where is everyone" and groups
 * to do it; this answers "what is arriving right now", and grouping a chronological stream by company
 * would destroy the only ordering it has. That is a deliberate absence rather than an omission — the
 * console standard governs listings you scan, and a feed is one you watch.
 *
 * <p>Raw, not effective: a punch the burst logic absorbed still appears, badged. The two views
 * disagreeing is the point — that is where a drifting clock or an unreadable finger shows up.
 */
export function PunchFeed({ siteId }: { siteId: string }) {
  const [scope, setScope] = React.useState<string | null>(siteId);
  const [deviceId, setDeviceId] = React.useState<string>('');
  const [attribution, setAttribution] = React.useState<Attribution>('ALL');
  const [day, setDay] = React.useState<string>('');

  const sitesQuery = useApiQuery(iclockKeys.sites(), listSites, { retry: false });
  const devicesQuery = useApiQuery(iclockKeys.devices(), (s) => listDevices(undefined, s), {
    retry: false,
  });
  const buildings = sitesQuery.data ?? [];
  const multiBuilding = buildings.length > 1;
  const effectiveScope = multiBuilding ? scope : siteId;

  const devices = (devicesQuery.data ?? []).filter(
    (d) => !effectiveScope || d.siteId === effectiveScope,
  );

  const query = useApiQuery<Feed>(
    iclockKeys.feed(effectiveScope, deviceId || null, day || null, attribution),
    (signal) =>
      getPunchFeed(
        { siteId: effectiveScope, deviceId: deviceId || null, shiftDate: day || null, attribution },
        signal,
      ),
    {
      retry: false,
      // Only tail live when looking at the current shift day; scrolling history should stay still.
      refetchInterval: day ? false : REFETCH_MS,
      placeholderData: (prev) => prev,
    },
  );

  if (query.isLoading) return <LoadingSkeleton lines={10} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const feed = query.data;

  return (
    <div className="space-y-4">
      <div className="sticky top-0 z-10 -mx-1 flex flex-wrap items-center gap-2 bg-background/95 px-1 py-2 backdrop-blur">
        {multiBuilding ? (
          <Combobox
            ariaLabel="Building"
            options={buildings.map((b) => ({ value: b.id, label: b.name }))}
            value={scope}
            onChange={(v) => setScope(v || null)}
            emptyOptionLabel="All buildings"
            className="w-48"
          />
        ) : null}
        <Combobox
          ariaLabel="Terminal"
          options={devices.map((d) => ({
            value: d.id,
            label: d.name ?? d.serialNumber,
            hint: d.serialNumber,
          }))}
          value={deviceId}
          onChange={setDeviceId}
          emptyOptionLabel="All terminals"
          className="w-52"
        />
        <Combobox
          ariaLabel="Attribution"
          options={[
            { value: 'ALL', label: 'All punches' },
            { value: 'UNKNOWN', label: 'Unknown pins only' },
            { value: 'ROSTERED', label: 'Rostered only' },
          ]}
          value={attribution}
          onChange={(v) => setAttribution(v as Attribution)}
          className="w-48"
        />
        <Input
          type="date"
          value={day}
          max={istTodayIso()}
          onChange={(e) => setDay(e.target.value)}
          className="w-40"
          aria-label="Shift day"
        />
        {day ? (
          <Button variant="ghost" size="sm" onClick={() => setDay('')}>
            Back to live
          </Button>
        ) : null}
        <span className="ml-auto inline-flex items-center gap-2 text-xs text-muted-foreground">
          {day ? (
            <>Showing {istDayLabel(day)}</>
          ) : (
            <>
              <span className="relative flex size-2">
                <span className="absolute inline-flex size-full animate-ping rounded-full bg-success opacity-60" />
                <span className="relative inline-flex size-2 rounded-full bg-success" />
              </span>
              Live · tonight
            </>
          )}
          <span className="tabular-nums">{feed.total} punches</span>
        </span>
      </div>

      {feed.rows.length === 0 ? (
        <EmptyState
          icon={Radio}
          title={attribution === 'UNKNOWN' ? 'Every punch is attributed' : 'Nothing yet'}
          description={
            attribution === 'UNKNOWN'
              ? 'No unattributed punches on this shift day — every pin that has tapped resolves to someone on the roster.'
              : 'No punches have arrived for this shift day. Punches appear here the moment a terminal sends them.'
          }
        />
      ) : (
        <Card className="overflow-hidden p-0">
          <ul className="divide-y divide-border">
            {feed.rows.map((r) => (
              <li key={r.rawPunchId}>
                <FeedRowView row={r} siteId={effectiveScope ?? siteId} />
              </li>
            ))}
          </ul>
        </Card>
      )}

      {feed.total > feed.rows.length ? (
        <p className="text-center text-xs text-muted-foreground">
          Showing the {feed.rows.length} most recent of {feed.total}. Narrow by terminal or attribution
          to see further back.
        </p>
      ) : null}
    </div>
  );
}

function FeedRowView({ row, siteId }: { row: FeedRow; siteId: string }) {
  const qc = useQueryClient();
  const [naming, setNaming] = React.useState(false);
  const [name, setName] = React.useState('');

  // The same create the inbox uses — one flow, so a person added here is added the same way.
  const create = useApiMutation(
    (personName: string) => upsertPerson(siteId, { pin: row.pin ?? '', name: personName.trim() || null }),
    {
      successMessage: 'Person added. Retry attribution to pick up their punches.',
      onSuccess: () => {
        setNaming(false);
        qc.invalidateQueries({ queryKey: iclockKeys.root });
      },
    },
  );

  return (
    <div className={cn('px-3 py-2', row.personId ? undefined : 'bg-warning/5')}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <span className="w-14 shrink-0 font-mono text-xs tabular-nums text-muted-foreground">
          {istTime(row.receivedAt)}
        </span>
        <DeviceRoleBadge area={row.area} direction={row.direction} />
        <span className="shrink-0 font-mono text-xs text-muted-foreground">{row.pin ?? '—'}</span>

        {row.personId ? (
          <Link
            href={`/super-admin/time-attendance/people/${row.personId}?site=${siteId}`}
            className="min-w-0 flex-1 truncate text-sm hover:underline"
          >
            {row.personName ?? 'Unnamed'}
            {row.companyName ? (
              <span className="ml-2 text-xs text-muted-foreground">{row.companyName}</span>
            ) : null}
          </Link>
        ) : (
          <span className="inline-flex min-w-0 flex-1 items-center gap-2">
            <Badge variant="warning">
              <AlertCircle className="size-3" aria-hidden />
              Unknown pin
            </Badge>
            {!naming ? (
              <Button
                variant="ghost"
                size="sm"
                className="h-7 px-2 text-xs"
                onClick={() => setNaming(true)}
              >
                <UserPlus className="size-3.5" />
                Add person
              </Button>
            ) : null}
          </span>
        )}

        <VerifyModeBadge mode={row.verifyMode} label={row.verifyLabel} />

        {row.collapsedAway ? (
          <Badge
            variant="neutral"
            title="Absorbed into a burst — the effective view keeps a different punch from this group."
          >
            <Layers className="size-3" aria-hidden />
            collapsed
          </Badge>
        ) : null}

        {/* The terminal's own clock. Shown only when it disagrees with arrival, because that gap is
            the signal — a reader drifting is invisible until someone puts the two side by side. */}
        {row.deviceTime && !row.deviceTime.startsWith(row.receivedAt.slice(0, 10)) ? (
          <span className="shrink-0 font-mono text-[11px] text-muted-foreground">
            device: {row.deviceTime}
          </span>
        ) : null}
      </div>

      {naming ? (
        <form
          className="mt-2 flex flex-wrap items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            create.mutate(name);
          }}
        >
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder={`Who is pin ${row.pin}? (leave blank to add them unnamed)`}
            className="min-w-0 flex-1"
            aria-label={`Name for pin ${row.pin}`}
            autoFocus
          />
          <Button type="submit" size="sm" disabled={create.isPending}>
            {create.isPending ? 'Adding…' : 'Add'}
          </Button>
          <Button type="button" size="sm" variant="ghost" onClick={() => setNaming(false)}>
            Cancel
          </Button>
        </form>
      ) : null}
    </div>
  );
}
