'use client';

import * as React from 'react';
import Link from 'next/link';
import { ArrowLeft, CalendarX2, Coffee, LogIn, LogOut, TriangleAlert } from 'lucide-react';
import { getPersonDay, iclockKeys } from '@/lib/api/iclock';
import { useApiQuery } from '@/lib/api/hooks';
import { Card } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Input } from '@/components/ui/input';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { surface } from '@/components/ui/surface';
import { istDayLabel, istTime, istTodayIso, formatDuration } from '@/lib/date';
import { cn } from '@/lib/utils';
import { ConsoleError } from './console-error';

/**
 * How long a session lasted, or null when it cannot honestly be said.
 *
 * Two open ends, and neither may be quietly filled in. A session with no START is a real shape — an
 * OUT the pipeline could not pair with an IN — and `new Date(null)` silently yields the 1970 epoch,
 * which renders as a half-million-hour day. A session with no END is only still running if the shift
 * day is TODAY; on any earlier day the person plainly went home and the missing OUT was never
 * recorded, so counting to the current clock invents weeks of attendance.
 */
function elapsedSeconds(from: string | null, to: string | null, isToday: boolean): number | null {
  if (!from) return null;
  if (!to && !isToday) return null;
  const start = new Date(from).getTime();
  const end = to ? new Date(to).getTime() : Date.now();
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return Math.max(0, Math.floor((end - start) / 1000));
}

export function PersonDayView({ personId, siteId }: { personId: string; siteId: string | null }) {
  // Empty means "let the server decide". Only the server knows which shift profile this person is
  // on, and therefore which shift day is theirs right now; seeding this with the IST CALENDAR date
  // showed every night worker an empty grid between midnight and the 11:30 cut, because at 02:00
  // their shift day is still yesterday.
  const [date, setDate] = React.useState('');

  const query = useApiQuery(
    iclockKeys.personDay(personId, date),
    (signal) => getPersonDay(personId, date, signal),
    { retry: false },
  );

  const backHref = `/super-admin/time-attendance/people${siteId ? `?site=${siteId}` : ''}`;

  if (query.isLoading) return <LoadingSkeleton lines={8} />;
  if (query.isError || !query.data) return <ConsoleError error={query.error} />;

  const d = query.data;
  // Answered by the server per the person's own shift, not guessed from the calendar.
  const isToday = d.current;
  const onSite = d.sessions.filter((s) => !s.cafeteria).map((s) => elapsedSeconds(s.from, s.to, isToday));
  const totalOnSite = onSite.reduce((acc: number, s) => acc + (s ?? 0), 0);
  // A total built partly from sessions that could not be measured is not a total. Saying so beats
  // printing a confident number that is quietly missing someone's afternoon.
  const totalIsPartial = onSite.some((s) => s === null);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Link
            href={backHref}
            className="inline-flex items-center gap-1.5 text-sm text-muted-foreground transition-colors hover:text-foreground"
          >
            <ArrowLeft className="size-3.5" />
            Back to people
          </Link>
          <h1 className="mt-2 text-2xl font-semibold tracking-tight">
            {d.name ?? <span className="text-muted-foreground">Unnamed</span>}
          </h1>
          <p className="font-mono text-sm text-muted-foreground">Pin {d.pin}</p>
        </div>
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="day">
            Shift day
          </label>
          <Input
            id="day"
            type="date"
            value={date || d.shiftDate}
            max={istTodayIso()}
            onChange={(e) => setDate(e.target.value)}
            className="w-44"
          />
        </div>
      </div>

      {d.punches.length === 0 ? (
        <EmptyState
          icon={CalendarX2}
          title="No punches on this day"
          description={`Nothing was recorded for ${istDayLabel(d.shiftDate)}. They may have been off, or punching on a terminal that is not claimed yet.`}
        />
      ) : (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <Card className="p-5">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                First in
              </div>
              <div className="mt-2 text-2xl font-semibold tabular-nums">
                {d.firstIn ? istTime(d.firstIn) : '—'}
              </div>
            </Card>
            <Card className="p-5">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                Last out
              </div>
              <div className="mt-2 text-2xl font-semibold tabular-nums">
                {d.lastOut ? istTime(d.lastOut) : '—'}
              </div>
            </Card>
            <Card className="p-5">
              <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
                On site
              </div>
              <div className="mt-2 text-2xl font-semibold tabular-nums">
                {formatDuration(totalOnSite)}
                {totalIsPartial ? <span className="text-base text-muted-foreground">+</span> : null}
              </div>
              {totalIsPartial ? (
                <div className="mt-1 text-xs text-warning">
                  At least — one session has no matching punch
                </div>
              ) : null}
            </Card>
          </div>

          {d.sessions.length > 0 ? (
            <Card className="p-6">
              <h2 className="mb-4 text-base font-semibold">Sessions</h2>
              <ul className="space-y-2">
                {d.sessions.map((s, i) => (
                  <li
                    key={`${s.from}-${i}`}
                    className={cn(surface('subtle'), 'flex flex-wrap items-center gap-3 px-4 py-3')}
                  >
                    {s.cafeteria ? (
                      <Coffee className="size-4 shrink-0 text-primary" />
                    ) : (
                      <LogIn className="size-4 shrink-0 text-success" />
                    )}
                    <span className="tabular-nums">
                      {s.from ? istTime(s.from) : 'no entry recorded'} —{' '}
                      {s.to ? istTime(s.to) : isToday ? 'still open' : 'no exit recorded'}
                    </span>
                    {(() => {
                      const secs = elapsedSeconds(s.from, s.to, isToday);
                      return (
                        <span className="text-sm text-muted-foreground">
                          {secs === null ? 'duration unknown' : formatDuration(secs)}
                        </span>
                      );
                    })()}
                    {s.cafeteria ? <Badge variant="primarySoft">Cafeteria</Badge> : null}
                    {/* An inferred pairing is labelled as one. Presenting a guessed OUT as an observed
                        fact is how a payroll dispute starts. */}
                    {s.estimated ? (
                      <Badge
                        variant="warning"
                        title="No matching punch was recorded — this pairing was inferred."
                      >
                        Estimated
                      </Badge>
                    ) : null}
                  </li>
                ))}
              </ul>
            </Card>
          ) : null}

          <Card className="p-6">
            <h2 className="mb-4 text-base font-semibold">Punches</h2>
            <ol className="relative space-y-4 border-l border-border pl-6">
              {d.punches.map((p) => (
                <li key={p.id} className="relative">
                  <span
                    className={cn(
                      'absolute -left-[1.9rem] top-1 flex size-5 items-center justify-center rounded-full ring-4 ring-card',
                      p.direction === 'IN' ? 'bg-success/15 text-success' : 'bg-muted text-muted-foreground',
                    )}
                  >
                    {p.direction === 'IN' ? (
                      <LogIn className="size-3" />
                    ) : (
                      <LogOut className="size-3" />
                    )}
                  </span>
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium tabular-nums">{istTime(p.effectiveAt)}</span>
                    <Badge variant={p.direction === 'IN' ? 'success' : 'neutral'}>
                      {p.direction === 'IN' ? 'In' : 'Out'}
                    </Badge>
                    {p.area ? <Badge variant="outline">{p.area}</Badge> : null}
                    {p.anomaly ? (
                      <Badge variant="danger">
                        <TriangleAlert className="size-3" />
                        {p.anomaly}
                      </Badge>
                    ) : null}
                  </div>
                  {/* Burst collapse is invisible unless the UI says it happened — otherwise "1 punch"
                      and "9 punches collapsed into 1" look identical and neither can be checked. */}
                  {p.burstCount > 1 ? (
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      {p.burstCount} reads collapsed
                      {p.burstFirstAt && p.burstLastAt
                        ? ` (${istTime(p.burstFirstAt)}–${istTime(p.burstLastAt)})`
                        : ''}
                    </p>
                  ) : null}
                </li>
              ))}
            </ol>
          </Card>
        </>
      )}
    </div>
  );
}
