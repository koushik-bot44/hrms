'use client';

import * as React from 'react';
import { Activity, LogIn, LogOut } from 'lucide-react';
import { attendanceKeys, getTeamActivity } from '@/lib/api/attendance';
import { useApiQuery } from '@/lib/api/hooks';
import { absoluteTime, istDateTime, relativeTime } from '@/lib/date';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';

const PAGE = 30;

/**
 * Pull-based "Attendance activity" feed (§8a): the manager's team clock-in/out punches, newest first,
 * derived from the audit log. Deliberately NOT wired to the notification bell — the manager checks it
 * here rather than being pinged. Refetches on window focus so it stays reasonably fresh.
 */
export function AttendanceActivityFeed() {
  const [size, setSize] = React.useState(PAGE);

  const query = useApiQuery(
    attendanceKeys.teamActivity(size),
    // Page 0 with a growing size acts as "load more" while keeping a single, stable feed query.
    (signal) => getTeamActivity(0, size, signal),
    { placeholderData: (prev) => prev, refetchOnWindowFocus: true, staleTime: 15_000 },
  );

  const data = query.data;
  const hasMore = data ? data.totalElements > data.content.length : false;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Attendance activity</CardTitle>
        <CardDescription>Recent clock-ins and clock-outs across your team.</CardDescription>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="space-y-3">
            {Array.from({ length: 5 }).map((_, i) => (
              <Skeleton key={i} className="h-10 w-full" />
            ))}
          </div>
        ) : query.isError ? (
          <div className="py-8 text-center text-sm text-destructive">
            Couldn&rsquo;t load activity.{' '}
            <button type="button" onClick={() => query.refetch()} className="underline">
              Retry
            </button>
          </div>
        ) : !data || data.content.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center text-muted-foreground">
            <Activity className="size-6" />
            <p className="text-sm">No attendance activity yet.</p>
          </div>
        ) : (
          <>
            <ul className="space-y-3">
              {data.content.map((e, i) => {
                const isIn = e.type === 'IN';
                return (
                  <li key={`${e.at}-${e.employeeId}-${i}`} className="flex items-start gap-3 text-sm">
                    <span
                      className={
                        isIn
                          ? 'mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-success/10 text-success'
                          : 'mt-0.5 flex size-7 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground'
                      }
                    >
                      {isIn ? <LogIn className="size-3.5" /> : <LogOut className="size-3.5" />}
                    </span>
                    <div className="min-w-0 flex-1">
                      <p className="truncate">
                        <span className="font-medium">{e.fullName ?? e.employeeCode ?? 'Employee'}</span>{' '}
                        <span className="text-muted-foreground">
                          {isIn ? 'clocked in' : 'clocked out'}
                        </span>
                      </p>
                      <p className="text-xs text-muted-foreground" title={absoluteTime(e.at)}>
                        {relativeTime(e.at)} · {istDateTime(e.at)}
                      </p>
                    </div>
                  </li>
                );
              })}
            </ul>
            {hasMore ? (
              <div className="pt-4 text-center">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={query.isFetching}
                  onClick={() => setSize((s) => s + PAGE)}
                >
                  {query.isFetching ? 'Loading…' : 'Load more'}
                </Button>
              </div>
            ) : null}
          </>
        )}
      </CardContent>
    </Card>
  );
}
