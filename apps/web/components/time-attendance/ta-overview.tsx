'use client';

import Link from 'next/link';
import { CalendarClock, Router, Upload, Users } from 'lucide-react';
import { getOverview, getUnmappedInbox, iclockKeys } from '@/lib/api/iclock';
import { useApiQuery } from '@/lib/api/hooks';
import { Card } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { istDayLabel } from '@/lib/date';
import { cn } from '@/lib/utils';
import { DeviceHealthBadge } from './device-health-badge';
import { InboxAlert } from './section-tabs';
import { ConsoleError } from './console-error';

/** One headline number. Tabular figures so the row of tiles doesn't jitter as counts change. */
function Stat({
  label,
  value,
  hint,
  tone,
}: {
  label: string;
  value: number | string;
  hint?: string;
  tone?: 'default' | 'warning';
}) {
  return (
    <Card className="p-5">
      <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
        {label}
      </div>
      <div
        className={cn(
          'mt-2 text-3xl font-semibold tabular-nums tracking-tight',
          tone === 'warning' ? 'text-warning' : 'text-foreground',
        )}
      >
        {value}
      </div>
      {hint ? <div className="mt-1 text-xs text-muted-foreground">{hint}</div> : null}
    </Card>
  );
}

/**
 * Presence split as a proportional bar.
 *
 * A CSS bar rather than a charting library: with four buckets and a roster that is empty on day one, a
 * donut would import a chart runtime to draw three rectangles, and an empty donut reads as a rendering
 * failure where an empty bar reads as "nobody has arrived".
 */
function PresenceBar({
  present,
  cafeteria,
  left,
  notArrived,
}: {
  present: number;
  cafeteria: number;
  left: number;
  notArrived: number;
}) {
  const total = present + cafeteria + left + notArrived;
  if (total === 0) return null;
  const seg = [
    { n: present, cls: 'bg-success', label: 'On site' },
    { n: cafeteria, cls: 'bg-primary', label: 'Cafeteria' },
    { n: left, cls: 'bg-muted-foreground/40', label: 'Left' },
    { n: notArrived, cls: 'bg-warning/50', label: 'Not arrived' },
  ].filter((s) => s.n > 0);

  return (
    <div className="space-y-2">
      <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-muted">
        {seg.map((s) => (
          <div
            key={s.label}
            className={s.cls}
            style={{ width: `${(s.n / total) * 100}%` }}
            title={`${s.label}: ${s.n}`}
          />
        ))}
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
        {seg.map((s) => (
          <span key={s.label} className="inline-flex items-center gap-1.5">
            <span className={cn('size-2 rounded-full', s.cls)} />
            {s.label} <span className="tabular-nums text-foreground">{s.n}</span>
          </span>
        ))}
      </div>
    </div>
  );
}

export function TaOverview({ siteId }: { siteId: string }) {
  const overview = useApiQuery(
    iclockKeys.overview(siteId),
    (signal) => getOverview(siteId, signal),
    { retry: false },
  );
  const inbox = useApiQuery(
    iclockKeys.inbox(siteId),
    (signal) => getUnmappedInbox(siteId, signal),
    { retry: false },
  );

  if (overview.isLoading) return <LoadingSkeleton lines={8} />;
  if (overview.isError || !overview.data) return <ConsoleError error={overview.error} />;

  const o = overview.data;
  const liveUnmapped = inbox.data?.live.length ?? 0;

  // EMPTY STATE — the roster has never been imported. This is what the operator meets on day one, and
  // every other tile would read as zero for a reason they cannot diagnose, so the screen says which
  // reason it is and hands them the one action that fixes it.
  if (o.rosterSize === 0) {
    return (
      <EmptyState
        icon={Users}
        title="No roster yet"
        description="Attendance needs to know who each pin belongs to. Import the roster and every punch already captured from the terminals will resolve retroactively."
        action={
          <Button asChild>
            <Link href={`/super-admin/time-attendance/people?site=${siteId}`}>
              <Upload className="size-4" />
              Import the roster
            </Link>
          </Button>
        }
      />
    );
  }

  return (
    <div className="space-y-6">
      <InboxAlert count={liveUnmapped} siteId={siteId} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="On the roster" value={o.rosterSize} hint="People enrolled at this site" />
        <Stat label="Present now" value={o.presentNow} hint="In office or cafeteria" />
        <Stat
          label="Not arrived"
          value={o.notArrived}
          tone={o.notArrived > 0 ? 'warning' : 'default'}
          hint={`${o.arrived} arrived today`}
        />
        <Stat label="Punches today" value={o.punchesToday} hint="After burst collapse" />
      </div>

      <Card className="p-6">
        <div className="mb-4 flex items-center justify-between gap-4">
          <div>
            <h2 className="text-base font-semibold">Where everyone is</h2>
            <p className="text-sm text-muted-foreground">
              <CalendarClock className="mr-1 inline size-3.5 align-[-2px]" />
              Shift day {istDayLabel(o.shiftDate)}
            </p>
          </div>
          <Button asChild variant="outline" size="sm">
            <Link href={`/super-admin/time-attendance/live?site=${siteId}`}>Open live board</Link>
          </Button>
        </div>
        {/* `presentNow` already includes the cafeteria, so it is ONE bucket here rather than two.
            Splitting it would double-count; the live board is where the split is real. */}
        <PresenceBar
          present={o.presentNow}
          cafeteria={0}
          left={Math.max(0, o.arrived - o.presentNow)}
          notArrived={o.notArrived}
        />
      </Card>

      <Card className="p-6">
        <div className="mb-4 flex items-center justify-between gap-4">
          <h2 className="text-base font-semibold">Terminals</h2>
          <Button asChild variant="ghost" size="sm">
            <Link href={`/super-admin/time-attendance/devices?site=${siteId}`}>Manage</Link>
          </Button>
        </div>
        {o.devices.length === 0 ? (
          <EmptyState
            icon={Router}
            title="No terminals claimed"
            description="Punches are captured from any terminal that reaches the server, but they can only be attributed once the terminal is claimed to this site."
            className="py-10"
            action={
              <Button asChild variant="outline">
                <Link href={`/super-admin/time-attendance/devices?site=${siteId}`}>
                  Claim a terminal
                </Link>
              </Button>
            }
          />
        ) : (
          <ul className="divide-y divide-border">
            {o.devices.map((d) => (
              <li
                key={d.serialNumber}
                className="flex flex-wrap items-center justify-between gap-3 py-3 first:pt-0 last:pb-0"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{d.name ?? d.serialNumber}</div>
                  <div className="font-mono text-xs text-muted-foreground">
                    {d.serialNumber}
                    {d.direction ? ` · ${d.direction}` : ''}
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <span className="text-sm tabular-nums text-muted-foreground">
                    {d.punchesToday} today
                  </span>
                  <DeviceHealthBadge
                    healthy={d.healthy}
                    minutesSinceSeen={d.minutesSinceSeen}
                    lastSeenAt={d.lastSeenAt}
                    status={d.status}
                  />
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}
