'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Coffee, LogIn, LogOut, Play } from 'lucide-react';
import { useApiMutation } from '@/lib/api/hooks';
import { attendanceKeys, clockIn, clockOut, endBreak, startBreak } from '@/lib/api/attendance';
import { formatDuration, formatElapsed, istTime } from '@/lib/date';
import { useClockStatus } from '@/components/attendance/use-clock-status';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * The clock control (§8a v2): one prominent state driven by the server status. When clocked in it shows the
 * live elapsed timer + Start/End Break + a LATE marker (if late today); worked totals exclude break time.
 * The server is the sole time source — the timers are display-only.
 */
export function ClockCard() {
  const queryClient = useQueryClient();
  const status = useClockStatus();
  const open = status.data?.open ?? false;
  const openSince = status.data?.openSince ?? null;
  const onBreak = status.data?.onBreak ?? false;
  const breakOpenSince = status.data?.breakOpenSince ?? null;
  const isLateToday = status.data?.isLateToday ?? false;

  // Live timer: re-render each second while a session is open (drives both the session + break elapsed).
  const [nowMs, setNowMs] = React.useState(() => Date.now());
  React.useEffect(() => {
    if (!open) return;
    setNowMs(Date.now());
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [open, openSince, onBreak]);

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: attendanceKeys.status });
    void queryClient.invalidateQueries({ queryKey: ['attendance', 'me'] });
  };

  const inMut = useApiMutation(clockIn, { successMessage: 'Clocked in', onSettled: refresh });
  const outMut = useApiMutation(clockOut, { successMessage: 'Clocked out', onSettled: refresh });
  const breakInMut = useApiMutation(startBreak, { successMessage: 'Break started', onSettled: refresh });
  const breakOutMut = useApiMutation(endBreak, { successMessage: 'Break ended', onSettled: refresh });
  const busy =
    inMut.isPending || outMut.isPending || breakInMut.isPending || breakOutMut.isPending;

  if (status.isLoading) {
    return (
      <Card>
        <CardContent className="p-6">
          <Skeleton className="h-20 w-full" />
        </CardContent>
      </Card>
    );
  }

  const elapsed = openSince ? Math.floor((nowMs - new Date(openSince).getTime()) / 1000) : 0;
  const breakElapsed = breakOpenSince
    ? Math.floor((nowMs - new Date(breakOpenSince).getTime()) / 1000)
    : 0;

  return (
    <Card>
      <CardContent className="flex flex-col items-start gap-5 p-6 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-1">
          {!open ? (
            <>
              <div className="text-sm text-muted-foreground">You&rsquo;re clocked out</div>
              <div className="text-3xl font-medium">Ready to start</div>
            </>
          ) : onBreak ? (
            <>
              <div className="flex items-center gap-2 text-sm text-warning">
                <Coffee className="size-4" />
                On break since {istTime(breakOpenSince!)}
              </div>
              <div className="font-mono text-4xl font-medium tabular-nums text-warning">
                {formatElapsed(breakElapsed)}
              </div>
            </>
          ) : (
            <>
              <div className="flex flex-wrap items-center gap-2 text-sm text-success">
                <span className="size-2 rounded-full bg-success" />
                Clocked in since {istTime(openSince!)}
                {isLateToday ? (
                  <Badge variant="warning" className="ml-1">
                    LATE
                  </Badge>
                ) : null}
              </div>
              <div className="font-mono text-4xl font-medium tabular-nums">{formatElapsed(elapsed)}</div>
            </>
          )}
          <div className="pt-1 text-xs text-muted-foreground">
            Today worked{' '}
            <span className="font-medium text-foreground">
              {formatDuration(status.data?.todaySeconds ?? 0)}
            </span>
            <span className="px-1.5">·</span>
            This week{' '}
            <span className="font-medium text-foreground">
              {formatDuration(status.data?.weekSeconds ?? 0)}
            </span>
          </div>
        </div>

        <div className="flex w-full flex-col gap-2 sm:w-auto sm:flex-row">
          {!open ? (
            <Button size="lg" disabled={busy} onClick={() => inMut.mutate()} className="w-full sm:w-auto">
              <LogIn />
              {inMut.isPending ? 'Logging in…' : 'Log In'}
            </Button>
          ) : onBreak ? (
            <Button
              size="lg"
              disabled={busy}
              onClick={() => breakOutMut.mutate()}
              className="w-full sm:w-auto"
            >
              <Play />
              {breakOutMut.isPending ? 'Resuming…' : 'End Break'}
            </Button>
          ) : (
            <>
              <Button
                size="lg"
                variant="outline"
                disabled={busy}
                onClick={() => breakInMut.mutate()}
                className="w-full sm:w-auto"
              >
                <Coffee />
                {breakInMut.isPending ? 'Starting…' : 'Start Break'}
              </Button>
              <Button
                size="lg"
                variant="destructive"
                disabled={busy}
                onClick={() => outMut.mutate()}
                className="w-full sm:w-auto"
              >
                <LogOut />
                {outMut.isPending ? 'Logging out…' : 'Log Out'}
              </Button>
            </>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
