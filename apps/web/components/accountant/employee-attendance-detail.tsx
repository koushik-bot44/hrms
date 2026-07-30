'use client';

import * as React from 'react';
import { Area, AreaChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { ArrowLeft, CalendarClock, Download } from 'lucide-react';
import type { EmployeeMonthSummary } from '@/lib/contract';
import {
  getEmployeeAttendanceMonthly,
  getEmployeeAttendanceSummary,
  type AttendanceSelection,
} from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { formatDuration, monthLabel } from '@/lib/date';
import { downloadCsv, toCsv } from '@/lib/csv';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { PeriodPicker, selectionLabel, lateLabel } from '@/components/accountant/period-picker';
import { AttendanceComposition } from '@/components/accountant/attendance-composition';
import { LiveIndicator } from '@/components/dashboard/live-indicator';
import { cn } from '@/lib/utils';

/** The employee's initial for the avatar chip. */
const initial = (name?: string | null) => (name?.trim()?.[0] ?? 'E').toUpperCase();

const REFRESH_MS = 45_000;

const hm = (seconds: number) => formatDuration(seconds);
/** Decimal hours for spreadsheet-friendly CSV values (2dp). */
const hours = (seconds: number) => Math.round((seconds / 3600) * 100) / 100;

/**
 * One employee's attendance detail (§8a, read-only): a worked-vs-break DONUT (the only same-unit split —
 * counts never go in the pie), separate stat cards for the counts, and a month-wise trend from the series.
 * The month selector focuses the summary/donut; the series shows history. Live-on-load + polling.
 */
export function EmployeeAttendanceDetail({
  employeeId,
  employeeName,
  selection,
  onSelectionChange,
  onBack,
}: {
  employeeId: string;
  employeeName?: string | null;
  selection: AttendanceSelection;
  onSelectionChange: (selection: AttendanceSelection) => void;
  onBack?: () => void;
}) {
  const activeMonth = 'from' in selection ? null : selection.month;
  const summary = useApiQuery(
    ['viewer-emp-attendance', employeeId, selection],
    (signal) => getEmployeeAttendanceSummary(employeeId, selection, signal),
    { refetchOnWindowFocus: true, refetchInterval: REFRESH_MS, placeholderData: (p) => p },
  );
  const series = useApiQuery(
    ['viewer-emp-attendance-monthly', employeeId],
    (signal) => getEmployeeAttendanceMonthly(employeeId, 6, signal),
    { refetchOnWindowFocus: true },
  );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          {onBack ? (
            <Button type="button" variant="outline" size="sm" onClick={onBack}>
              <ArrowLeft />
              Back
            </Button>
          ) : null}
          <span
            aria-hidden
            className="flex size-9 shrink-0 items-center justify-center rounded-full bg-surface-tint text-sm font-semibold text-primary"
          >
            {initial(employeeName)}
          </span>
          <div>
            <h3 className="text-sm font-semibold">{employeeName ?? 'Employee'}</h3>
            <p className="text-xs text-muted-foreground">
              Attendance · {selectionLabel(selection, monthLabel)}
              {summary.data ? (
                <>
                  {' · '}
                  {summary.data.clockedInNow ? (
                    <Badge variant="success" className="ml-1 align-middle">
                      Clocked in now
                    </Badge>
                  ) : (
                    <Badge variant="neutral" className="ml-1 align-middle">
                      Not clocked in
                    </Badge>
                  )}
                </>
              ) : null}
            </p>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <LiveIndicator className="hidden sm:inline-flex" />
          <PeriodPicker value={selection} onChange={onSelectionChange} />
        </div>
      </div>

      {summary.isLoading ? (
        <LoadingSkeleton lines={6} />
      ) : summary.isError ? (
        <EmptyState
          icon={CalendarClock}
          title="Couldn't load attendance"
          description={summary.error?.message ?? 'Please try again.'}
        />
      ) : summary.data ? (
        <SummaryBody data={summary.data} selection={selection} />
      ) : null}

      <MonthlyReport
        employeeId={employeeId}
        months={series.data?.months ?? []}
        loading={series.isLoading}
        activeMonth={activeMonth}
        onPickMonth={(m) => onSelectionChange({ mode: 'month', month: m })}
      />
    </div>
  );
}

function SummaryBody({ data, selection }: { data: EmployeeMonthSummary; selection: AttendanceSelection }) {
  const gross = data.timeComposition.workedSeconds + data.timeComposition.breakSeconds;
  const noData = gross === 0 && data.daysPresent === 0 && data.leaveDaysTotal === 0;
  if (noData) {
    return (
      <EmptyState
        icon={CalendarClock}
        title="No attendance in this window"
        description="This employee has no sessions or leave recorded for the selected window."
      />
    );
  }
  // In Today mode, past-only Absences and a one-day Adherence would mislead — hide them (§8a).
  const isToday = selection.mode === 'today';
  return (
    <AttendanceComposition
      data={{
        workedSeconds: data.workedSeconds,
        breakSeconds: data.breakSeconds,
        daysPresent: data.daysPresent,
        lateLogins: data.lateLogins,
        leaveDaysTotal: data.leaveDaysTotal,
        leavesByType: data.leavesByType,
        expectedDays: data.expectedDays,
        workingDays: data.workingDays,
        unapprovedAbsences: data.unapprovedAbsences,
        adherencePct: data.adherencePct,
        workingDaysDefinition: data.workingDaysDefinition,
      }}
      lateLabel={lateLabel(selection)}
      hideAbsences={isToday}
      hideAdherence={isToday}
    />
  );
}

/** Export the loaded per-month series as CSV (no refetch). Hours are decimal (header-labeled). */
function exportEmployeeCsv(employeeId: string, months: EmployeeMonthSummary[]): void {
  const headers = [
    'Month',
    'Worked (h)',
    'Break (h)',
    'Days present',
    'Late logins',
    'Leave days',
    'Casual',
    'Sick',
    'Unpaid',
    'Working days',
    'Expected days',
    'Unapproved absences',
    'Adherence %',
  ];
  const rows = months.map((m) => [
    m.month,
    hours(m.workedSeconds),
    hours(m.breakSeconds),
    m.daysPresent,
    m.lateLogins,
    m.leaveDaysTotal,
    m.leavesByType.casual,
    m.leavesByType.sick,
    m.leavesByType.unpaid,
    m.workingDays,
    m.expectedDays,
    m.unapprovedAbsences,
    m.adherencePct ?? 'N/A', // null when there are no expected working days
  ]);
  const from = months[0]?.month ?? 'na';
  const to = months[months.length - 1]?.month ?? 'na';
  downloadCsv(`attendance_${employeeId}_${from}-${to}.csv`, toCsv(headers, rows));
}

function MonthlyReport({
  employeeId,
  months,
  loading,
  activeMonth,
  onPickMonth,
}: {
  employeeId: string;
  months: EmployeeMonthSummary[];
  loading: boolean;
  activeMonth: string | null;
  onPickMonth: (m: string) => void;
}) {
  // The series is inherently monthly, so every item's `month` is a real YYYY-MM (never the range null).
  const chart = months.map((m) => ({
    month: monthLabel(m.month ?? '').replace(/ \d{4}$/, ''),
    key: m.month ?? '',
    workedHours: Math.round((m.workedSeconds / 3600) * 10) / 10,
  }));
  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between space-y-0">
        <CardTitle className="text-base">Month-wise report</CardTitle>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={months.length === 0}
          onClick={() => exportEmployeeCsv(employeeId, months)}
        >
          <Download className="size-4" />
          Export CSV
        </Button>
      </CardHeader>
      <CardContent className="space-y-4">
        {loading ? (
          <LoadingSkeleton lines={4} />
        ) : months.length === 0 ? (
          <p className="text-sm text-muted-foreground">No history yet.</p>
        ) : (
          <>
            <div className="h-40">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart data={chart} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
                  <defs>
                    {/* Soft indigo gradient fill, token-derived. */}
                    <linearGradient id="workedArea" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stopColor="hsl(var(--primary))" stopOpacity={0.35} />
                      <stop offset="100%" stopColor="hsl(var(--primary))" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} width={36} />
                  <Tooltip
                    formatter={(v) => [`${Number(v)}h`, 'Worked']}
                    contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
                  />
                  <Area
                    type="monotone"
                    dataKey="workedHours"
                    stroke="hsl(var(--primary))"
                    strokeWidth={2}
                    fill="url(#workedArea)"
                  />
                </AreaChart>
              </ResponsiveContainer>
            </div>
            <div className="overflow-x-auto rounded-xl border">
              <table className="w-full text-sm">
                <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-4 py-3 font-medium">Month</th>
                    <th className="px-4 py-3 font-medium">Worked</th>
                    <th className="px-4 py-3 font-medium">Days present</th>
                    <th className="px-4 py-3 font-medium">Late</th>
                    <th className="px-4 py-3 font-medium">Leave days</th>
                    <th className="px-4 py-3 font-medium">Absent</th>
                  </tr>
                </thead>
                <tbody>
                  {[...months].reverse().map((m) => (
                    <tr
                      key={m.month ?? ''}
                      onClick={() => m.month && onPickMonth(m.month)}
                      className={cn(
                        'cursor-pointer border-t hover:bg-accent/40',
                        m.month === activeMonth && 'bg-primary/5',
                      )}
                    >
                      <td className="px-4 py-3 font-medium">{monthLabel(m.month ?? '')}</td>
                      <td className="px-4 py-3 tabular-nums">{hm(m.workedSeconds)}</td>
                      <td className="px-4 py-3 tabular-nums">{m.daysPresent}</td>
                      <td className="px-4 py-3 tabular-nums">{m.lateLogins}</td>
                      <td className="px-4 py-3 tabular-nums">{m.leaveDaysTotal}</td>
                      <td className={cn('px-4 py-3 tabular-nums', m.unapprovedAbsences > 0 && 'text-warning')}>
                        {m.unapprovedAbsences}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
