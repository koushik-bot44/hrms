'use client';

import * as React from 'react';
import { CalendarDays } from 'lucide-react';

/**
 * A static, display-only chip showing today's date (real current date). Computed on mount to avoid an
 * SSR/timezone hydration mismatch. Read-only — no picker, no data.
 */
export function TodayChip() {
  const [today, setToday] = React.useState('');
  React.useEffect(() => {
    setToday(
      new Date().toLocaleDateString(undefined, {
        weekday: 'short',
        month: 'short',
        day: 'numeric',
        year: 'numeric',
      }),
    );
  }, []);
  return (
    <span className="inline-flex items-center gap-2 rounded-full border bg-card px-3.5 py-2 text-sm">
      <CalendarDays className="size-4 text-primary" aria-hidden />
      <span className="tabular-nums text-foreground">{today || 'Today'}</span>
    </span>
  );
}
