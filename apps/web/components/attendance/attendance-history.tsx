'use client';

import * as React from 'react';
import { keepPreviousData } from '@tanstack/react-query';
import { CalendarClock } from 'lucide-react';
import type { MyAttendancePage } from '@/lib/contract';
import { useApiQuery } from '@/lib/api/hooks';
import {
  formatDuration,
  istDayLabel,
  istDaysAgoIso,
  istTime,
  istTodayIso,
} from '@/lib/date';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';

interface Props {
  /** Fetches one page of day-grouped history for the chosen IST range. */
  fetchPage: (from: string, to: string, page: number, signal?: AbortSignal) => Promise<MyAttendancePage>;
  /** react-query key factory (varies by owner: my history vs. a team employee). */
  queryKey: (from: string, to: string, page: number) => readonly unknown[];
  title?: string;
}

/**
 * Day-grouped attendance history (§8a) with an IST date-range filter and paging. All grouping/totals are
 * server-computed in Asia/Kolkata; open sessions render as "In progress" and add nothing to totals.
 */
export function AttendanceHistory({ fetchPage, queryKey, title = 'History' }: Props) {
  const [from, setFrom] = React.useState(() => istDaysAgoIso(13));
  const [to, setTo] = React.useState(() => istTodayIso());
  const [page, setPage] = React.useState(0);

  const query = useApiQuery(
    queryKey(from, to, page),
    (signal) => fetchPage(from, to, page, signal),
    { placeholderData: keepPreviousData },
  );
  const data = query.data;

  const onRange = (next: { from?: string; to?: string }) => {
    if (next.from !== undefined) setFrom(next.from);
    if (next.to !== undefined) setTo(next.to);
    setPage(0); // a new range restarts paging
  };

  return (
    <Card>
      <CardHeader className="flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
        <div className="space-y-1">
          <CardTitle className="text-base">{title}</CardTitle>
          {data ? (
            <p className="text-xs text-muted-foreground">
              {formatDuration(data.periodSeconds)} across this range
            </p>
          ) : null}
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <label className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">From</span>
            <Input
              type="date"
              value={from}
              max={to}
              onChange={(e) => onRange({ from: e.target.value })}
              className="h-8 w-auto"
            />
          </label>
          <label className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">To</span>
            <Input
              type="date"
              value={to}
              min={from}
              max={istTodayIso()}
              onChange={(e) => onRange({ to: e.target.value })}
              className="h-8 w-auto"
            />
          </label>
        </div>
      </CardHeader>

      <CardContent>
        {query.isLoading ? (
          <div className="space-y-3">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
          </div>
        ) : query.isError ? (
          <div className="py-8 text-center text-sm text-destructive">
            Couldn&rsquo;t load history.{' '}
            <button type="button" onClick={() => query.refetch()} className="underline">
              Retry
            </button>
          </div>
        ) : !data || data.days.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center text-muted-foreground">
            <CalendarClock className="size-6" />
            <p className="text-sm">No attendance in this range.</p>
          </div>
        ) : (
          <div className="space-y-5">
            {data.days.map((day) => (
              <div key={day.date} className="space-y-2">
                <div className="flex items-baseline justify-between">
                  <h3 className="text-sm font-medium">{istDayLabel(day.date)}</h3>
                  <span className="text-xs font-medium text-muted-foreground">
                    {formatDuration(day.totalSeconds)}
                  </span>
                </div>
                <ul className="divide-y rounded-md border">
                  {day.sessions.map((s) => (
                    <li
                      key={s.id}
                      className="flex items-center justify-between px-3 py-2 text-sm"
                    >
                      <span className="tabular-nums">
                        {istTime(s.clockInAt)}
                        <span className="px-1.5 text-muted-foreground">→</span>
                        {s.clockOutAt ? (
                          istTime(s.clockOutAt)
                        ) : (
                          <span className="text-success">In progress</span>
                        )}
                      </span>
                      <span className="text-muted-foreground">
                        {s.durationSeconds != null ? formatDuration(s.durationSeconds) : '—'}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>
        )}

        {data && data.totalPages > 1 ? (
          <div className="flex items-center justify-between pt-4 text-sm">
            <span className="text-xs text-muted-foreground">
              Page {data.page + 1} of {data.totalPages}
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={data.page === 0 || query.isFetching}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={data.page >= data.totalPages - 1 || query.isFetching}
                onClick={() => setPage((p) => p + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        ) : null}
      </CardContent>
    </Card>
  );
}
