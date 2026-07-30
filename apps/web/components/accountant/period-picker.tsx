'use client';

import * as React from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { AttendanceSelection } from '@/lib/api/accountant';
import { currentCycle, stepCycle } from '@/lib/attendance/payroll-cycle';
import { istDaysAgoIso, istMonthIso, istTodayIso } from '@/lib/date';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { cn } from '@/lib/utils';

const MONTH_INPUT =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring';

type Mode = AttendanceSelection['mode'];
const MODE_LABELS: Record<Mode, string> = {
  today: 'Today',
  month: 'Month',
  cycle: 'Payroll cycle',
  custom: 'Custom range',
};

/** The default reporting window: the current IST shift-month (month mode). */
export function defaultSelection(): AttendanceSelection {
  return { mode: 'month', month: istMonthIso() };
}

/** Build a fresh selection for a mode (seeded to a sensible default window). */
function selectionForMode(mode: Mode): AttendanceSelection {
  const today = istTodayIso();
  switch (mode) {
    case 'today':
      return { mode: 'today', from: today, to: today };
    case 'month':
      return { mode: 'month', month: istMonthIso() };
    case 'cycle':
      return { mode: 'cycle', ...currentCycle(today) };
    default:
      return { mode: 'custom', from: istDaysAgoIso(29), to: today };
  }
}

/**
 * The reporting-window control for the viewer attendance dashboards (§8a): Today | Month | Payroll cycle |
 * Custom range. All four resolve to a concrete window; Month keeps the `?month=` call, the others resolve
 * to a [from,to] range. The today-snapshot / "now" tiles stay live regardless of the window — a range only
 * re-scopes the ranged metrics (worked / present / leave / adherence / absences).
 */
export function PeriodPicker({
  value,
  onChange,
}: {
  value: AttendanceSelection;
  onChange: (selection: AttendanceSelection) => void;
}) {
  const today = istTodayIso();

  return (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <div className="inline-flex rounded-md border border-input p-0.5" role="group" aria-label="Report window">
        {(Object.keys(MODE_LABELS) as Mode[]).map((m) => (
          <button
            key={m}
            type="button"
            aria-pressed={value.mode === m}
            onClick={() => value.mode !== m && onChange(selectionForMode(m))}
            className={cn(
              'rounded px-2.5 py-1 text-xs font-medium transition-colors',
              value.mode === m
                ? 'bg-primary text-primary-foreground'
                : 'text-muted-foreground hover:text-foreground',
            )}
          >
            {MODE_LABELS[m]}
          </button>
        ))}
      </div>

      {value.mode === 'today' ? (
        <span className="text-xs text-muted-foreground">Live — {formatDay(value.from)}</span>
      ) : value.mode === 'month' ? (
        <label className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">Month</span>
          <input
            type="month"
            value={value.month}
            max={istMonthIso()}
            onChange={(e) => e.target.value && onChange({ mode: 'month', month: e.target.value })}
            className={MONTH_INPUT}
          />
        </label>
      ) : value.mode === 'cycle' ? (
        <div className="flex items-center gap-1.5">
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label="Previous cycle"
            onClick={() => onChange({ mode: 'cycle', ...stepCycle(value.from, -1) })}
          >
            <ChevronLeft className="size-4" />
          </Button>
          <span className="min-w-[9.5rem] text-center text-xs font-medium tabular-nums">
            {cycleLabel(value.from, value.to)}
          </span>
          <Button
            type="button"
            variant="outline"
            size="icon"
            aria-label="Next cycle"
            onClick={() => onChange({ mode: 'cycle', ...stepCycle(value.from, 1) })}
          >
            <ChevronRight className="size-4" />
          </Button>
        </div>
      ) : (
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">From</span>
            <Input
              type="date"
              value={value.from}
              max={value.to || today}
              onChange={(e) => e.target.value && onChange({ mode: 'custom', from: e.target.value, to: value.to })}
              className="w-auto"
            />
          </label>
          <label className="flex items-center gap-1.5">
            <span className="text-xs text-muted-foreground">To</span>
            <Input
              type="date"
              value={value.to}
              min={value.from}
              max={today}
              onChange={(e) => e.target.value && onChange({ mode: 'custom', from: value.from, to: e.target.value })}
              className="w-auto"
            />
          </label>
        </div>
      )}
    </div>
  );
}

/** A short human label of the active window, for dashboard headers. */
export function selectionLabel(selection: AttendanceSelection, monthLabel: (m: string) => string): string {
  if (selection.mode === 'month') return monthLabel(selection.month);
  if (selection.from === selection.to) {
    return selection.mode === 'today' ? `Today · ${formatDay(selection.from)}` : formatDay(selection.from);
  }
  return `${formatDay(selection.from)} – ${formatDay(selection.to)}`;
}

/** A file-name-safe token for the active window ("2026-07" / "2026-07-31" / "2026-06-26_2026-07-25"). */
export function selectionSlug(selection: AttendanceSelection): string {
  if (selection.mode === 'month') return selection.month;
  return selection.from === selection.to ? selection.from : `${selection.from}_${selection.to}`;
}

/** The window-aware label for a "late logins" tile ("Late today" / "Late this month" / …). */
export function lateLabel(selection: AttendanceSelection): string {
  switch (selection.mode) {
    case 'today':
      return 'Late today';
    case 'month':
      return 'Late this month';
    case 'cycle':
      return 'Late this cycle';
    default:
      return 'Late in range';
  }
}

/** A one-word noun for the window ("today" / "month" / "cycle" / "range") — for compact column headers. */
export function windowWord(selection: AttendanceSelection): string {
  return selection.mode === 'custom' ? 'range' : selection.mode;
}

/** A compact cycle range label, e.g. "26 Jun – 25 Jul 2026". */
function cycleLabel(from: string, to: string): string {
  const f = new Date(`${from}T00:00:00`);
  const t = new Date(`${to}T00:00:00`);
  if (Number.isNaN(f.getTime()) || Number.isNaN(t.getTime())) return `${from} – ${to}`;
  const day = (d: Date, withYear: boolean) =>
    d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', ...(withYear ? { year: 'numeric' } : {}) });
  const sameYear = f.getFullYear() === t.getFullYear();
  return `${day(f, !sameYear)} – ${day(t, true)}`;
}

function formatDay(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}
