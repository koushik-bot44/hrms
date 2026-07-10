'use client';

import * as React from 'react';
import { useAuth } from '@/components/auth-provider';
import { useApiQuery } from '@/lib/api/hooks';
import { attendanceKeys, getClockStatus } from '@/lib/api/attendance';

/**
 * The signed-in employee's clock status (§8a). Enabled only for a credentialed EMPLOYEE session (staff
 * have no attendance); refetched on focus so the sign-out reminder + the badge stay current. Returns the
 * react-query result — {@code data?.open} tells whether a session is open.
 */
export function useClockStatus() {
  const { session } = useAuth();
  const enabled = session?.type === 'EMPLOYEE' && Boolean(session.mailAddress);
  return useApiQuery(attendanceKeys.status, getClockStatus, {
    enabled,
    refetchOnWindowFocus: true,
    staleTime: 10_000,
  });
}

/**
 * Best-effort tab/browser-close nudge while a session is open (§8a): register a `beforeunload` handler so
 * the browser shows its GENERIC "Leave site?" prompt. NOTE: the prompt text is not customizable and
 * leaving cannot be prevented — this is only a reminder, never enforcement. The handler is removed as soon
 * as the employee is not clocked in.
 */
export function useBeforeUnloadWhenClockedIn(open: boolean): void {
  React.useEffect(() => {
    if (!open) return;
    const handler = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = ''; // required for Chrome to show the generic prompt
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  }, [open]);
}
