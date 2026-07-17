'use client';

import { istMonthIso } from '@/lib/date';

/** A native month input for the viewer attendance dashboards; capped at the current IST shift-month. */
export function MonthPicker({ value, onChange }: { value: string; onChange: (m: string) => void }) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="text-xs text-muted-foreground">Month</span>
      <input
        type="month"
        value={value}
        max={istMonthIso()}
        onChange={(e) => e.target.value && onChange(e.target.value)}
        className="h-9 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      />
    </label>
  );
}
