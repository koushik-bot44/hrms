'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { LogIn, LogOut } from 'lucide-react';
import { useApiMutation } from '@/lib/api/hooks';
import { attendanceKeys, clockIn, clockOut } from '@/lib/api/attendance';
import { formatDuration, formatElapsed, istTime } from '@/lib/date';
import { useClockStatus } from '@/components/attendance/use-clock-status';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

/**
 * The clock in / clock out control (§8a): one prominent button that toggles on the server-computed
 * status. When clocked in it shows "Clocked in since HH:MM" with a live elapsed timer, plus today's and
 * this week's completed totals. The server is the sole time source — the timer is display-only.
 */
export function ClockCard() {
  const queryClient = useQueryClient();
  const status = useClockStatus();
  const open = status.data?.open ?? false;
  const openSince = status.data?.openSince ?? null;

  // Live timer: re-render each second while a session is open. The elapsed value is derived from the
  // server's openSince, so nothing here is trusted as a time source — it only animates the display.
  const [nowMs, setNowMs] = React.useState(() => Date.now());
  React.useEffect(() => {
    if (!open) return;
    setNowMs(Date.now());
    const id = window.setInterval(() => setNowMs(Date.now()), 1000);
    return () => window.clearInterval(id);
  }, [open, openSince]);

  // Refresh both the status and the history list (day grouping/totals change on each punch).
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: attendanceKeys.status });
    void queryClient.invalidateQueries({ queryKey: ['attendance', 'me'] });
  };

  const inMut = useApiMutation(clockIn, { successMessage: 'Clocked in', onSettled: refresh });
  const outMut = useApiMutation(clockOut, { successMessage: 'Clocked out', onSettled: refresh });
  const busy = inMut.isPending || outMut.isPending;

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

  return (
    <Card>
      <CardContent className="flex flex-col items-start gap-5 p-6 sm:flex-row sm:items-center sm:justify-between">
        <div className="space-y-1">
          {open ? (
            <>
              <div className="flex items-center gap-2 text-sm text-success">
                <span className="size-2 rounded-full bg-success" />
                Clocked in since {istTime(openSince!)}
              </div>
              <div className="font-mono text-4xl font-medium tabular-nums">{formatElapsed(elapsed)}</div>
            </>
          ) : (
            <>
              <div className="text-sm text-muted-foreground">You&rsquo;re clocked out</div>
              <div className="text-3xl font-medium">Ready to start</div>
            </>
          )}
          <div className="pt-1 text-xs text-muted-foreground">
            Today{' '}
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

        {open ? (
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
        ) : (
          <Button
            size="lg"
            disabled={busy}
            onClick={() => inMut.mutate()}
            className="w-full sm:w-auto"
          >
            <LogIn />
            {inMut.isPending ? 'Logging in…' : 'Log In'}
          </Button>
        )}
      </CardContent>
    </Card>
  );
}
