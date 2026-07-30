'use client';

import * as React from 'react';
import type { AttendanceSelection } from '@/lib/api/accountant';
import { istDaysAgoIso, istMonthIso, istTodayIso } from '@/lib/date';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

const MONTH_INPUT =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

/** The default reporting window: the current IST shift-month (month mode). */
export function defaultSelection(): AttendanceSelection {
  return { month: istMonthIso() };
}

/**
 * The reporting-window control for the viewer attendance dashboards (§8a): a Month mode (native month
 * input, capped at the current IST shift-month) OR a Custom range mode (two date inputs, from ≤ to, capped
 * at today). Emits an {@link AttendanceSelection}. The today-snapshot tiles stay live regardless — a range
 * only re-scopes the ranged metrics (worked / present / leave / adherence / absences), never "now".
 */
export function PeriodPicker({
  value,
  onChange,
}: {
  value: AttendanceSelection;
  onChange: (selection: AttendanceSelection) => void;
}) {
  const range = 'from' in value ? value : null;
  const month = 'from' in value ? null : value.month;
  const today = istTodayIso();

  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <div className="inline-flex rounded-md border border-input p-0.5" role="group" aria-label="Report window">
        <button
          type="button"
          aria-pressed={!range}
          onClick={() => onChange({ month: istMonthIso() })}
          className={cn(
            'rounded px-2.5 py-1 text-xs font-medium transition-colors',
            !range ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          Month
        </button>
        <button
          type="button"
          aria-pressed={Boolean(range)}
          onClick={() => onChange({ from: istDaysAgoIso(29), to: today })}
          className={cn(
            'rounded px-2.5 py-1 text-xs font-medium transition-colors',
            range ? 'bg-primary text-primary-foreground' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          Custom range
        </button>
      </div>

      {range ? (
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">From</span>
            <Input
              type="date"
              value={range.from}
              max={range.to || today}
              onChange={(e) => e.target.value && onChange({ from: e.target.value, to: range.to })}
              className="w-auto"
            />
          </label>
          <label className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">To</span>
            <Input
              type="date"
              value={range.to}
              min={range.from}
              max={today}
              onChange={(e) => e.target.value && onChange({ from: range.from, to: e.target.value })}
              className="w-auto"
            />
          </label>
        </div>
      ) : (
        <label className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">Month</span>
          <input
            type="month"
            value={month ?? istMonthIso()}
            max={istMonthIso()}
            onChange={(e) => e.target.value && onChange({ month: e.target.value })}
            className={MONTH_INPUT}
          />
        </label>
      )}
    </div>
  );
}

/** A short human label of the active window, for dashboard headers ("July 2026" / "5 Jan – 20 Jan 2026"). */
export function selectionLabel(selection: AttendanceSelection, monthLabel: (m: string) => string): string {
  if ('from' in selection) {
    return `${formatDay(selection.from)} – ${formatDay(selection.to)}`;
  }
  return monthLabel(selection.month);
}

/** A file-name-safe token for the active window ("2026-07" / "2026-01-05_2026-01-20"). */
export function selectionSlug(selection: AttendanceSelection): string {
  return 'from' in selection ? `${selection.from}_${selection.to}` : selection.month;
}

function formatDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}
