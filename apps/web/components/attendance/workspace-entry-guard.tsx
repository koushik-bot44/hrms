'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CalendarClock, Coffee } from 'lucide-react';
import { useApiMutation } from '@/lib/api/hooks';
import { attendanceKeys, clockIn, endBreak } from '@/lib/api/attendance';
import { useClockStatus } from '@/components/attendance/use-clock-status';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

/**
 * Server-state-driven entry prompts (§8a v2). Mounted in the /workspace shell; it reads
 * {@code /attendance/me/status} on mount AND on window focus/visibility (NOT a live timer, so it survives
 * a slept machine). If the employee is NOT clocked in → a clock-in dialog (with Skip, which dismisses for
 * this session). If a break is still open → a return-from-break reminder (with End Break; re-armed on each
 * refocus while still on break).
 */
export function WorkspaceEntryGuard() {
  const queryClient = useQueryClient();
  const status = useClockStatus();
  const open = status.data?.open ?? false;
  const onBreak = status.data?.onBreak ?? false;

  const [skippedClockIn, setSkippedClockIn] = React.useState(false);
  const [dismissedBreak, setDismissedBreak] = React.useState(false);

  // Re-evaluate on return to the tab: refetch status + re-arm the break reminder (Skip stays sticky).
  React.useEffect(() => {
    const onFocus = () => {
      void queryClient.invalidateQueries({ queryKey: attendanceKeys.status });
      setDismissedBreak(false);
    };
    window.addEventListener('focus', onFocus);
    document.addEventListener('visibilitychange', onFocus);
    return () => {
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('visibilitychange', onFocus);
    };
  }, [queryClient]);

  const refresh = () => void queryClient.invalidateQueries({ queryKey: attendanceKeys.status });
  const inMut = useApiMutation(clockIn, { successMessage: 'Logged in', onSettled: refresh });
  const breakOutMut = useApiMutation(endBreak, { successMessage: 'Break ended', onSettled: refresh });

  // Nothing to prompt until we have status (the hook is disabled for non-credentialed sessions).
  if (!status.data) return null;

  const showClockIn = !open && !skippedClockIn;
  const showBreak = open && onBreak && !dismissedBreak;

  return (
    <>
      <Dialog open={showClockIn} onOpenChange={(o) => (!o ? setSkippedClockIn(true) : undefined)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <CalendarClock className="size-5" />
              Log in to start your shift
            </DialogTitle>
            <DialogDescription>
              You&rsquo;re not logged in. Log in now to start recording your working time.
            </DialogDescription>
          </DialogHeader>
          <div className="flex justify-end gap-2 pt-2">
            <Button variant="ghost" onClick={() => setSkippedClockIn(true)} disabled={inMut.isPending}>
              Skip
            </Button>
            <Button onClick={() => inMut.mutate()} disabled={inMut.isPending}>
              {inMut.isPending ? 'Logging in…' : 'Log In'}
            </Button>
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={showBreak} onOpenChange={(o) => (!o ? setDismissedBreak(true) : undefined)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <Coffee className="size-5" />
              You&rsquo;re on break
            </DialogTitle>
            <DialogDescription>
              End your break to resume working — break time isn&rsquo;t counted toward your hours.
            </DialogDescription>
          </DialogHeader>
          <div className="flex justify-end gap-2 pt-2">
            <Button
              variant="ghost"
              onClick={() => setDismissedBreak(true)}
              disabled={breakOutMut.isPending}
            >
              Not yet
            </Button>
            <Button onClick={() => breakOutMut.mutate()} disabled={breakOutMut.isPending}>
              {breakOutMut.isPending ? 'Resuming…' : 'End Break'}
            </Button>
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
