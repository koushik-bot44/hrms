'use client';

import * as React from 'react';
import {
  Bar,
  BarChart,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { ArrowLeft, CalendarClock, Download } from 'lucide-react';
import type { EmployeeMonthSummary } from '@/lib/contract';
import { getEmployeeAttendanceMonthly, getEmployeeAttendanceSummary } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { formatDuration, monthLabel } from '@/lib/date';
import { downloadCsv, toCsv } from '@/lib/csv';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { MonthPicker } from '@/components/accountant/month-picker';
import { cn } from '@/lib/utils';

// Theme-aware chart colors from the IHRMS design tokens (work each look via CSS vars).
const WORKED = 'hsl(var(--primary))';
const BREAK = 'hsl(var(--warning))';

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
  month,
  onMonthChange,
  onBack,
}: {
  employeeId: string;
  employeeName?: string | null;
  month: string;
  onMonthChange: (m: string) => void;
  onBack?: () => void;
}) {
  const summary = useApiQuery(
    ['viewer-emp-attendance', employeeId, month],
    (signal) => getEmployeeAttendanceSummary(employeeId, month, signal),
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
          <div>
            <h3 className="text-sm font-semibold">{employeeName ?? 'Employee'}</h3>
            <p className="text-xs text-muted-foreground">
              Attendance · {monthLabel(month)}
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
        <MonthPicker value={month} onChange={onMonthChange} />
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
        <SummaryBody data={summary.data} />
      ) : null}

      <MonthlyReport
        employeeId={employeeId}
        months={series.data?.months ?? []}
        loading={series.isLoading}
        activeMonth={month}
        onPickMonth={onMonthChange}
      />
    </div>
  );
}

function SummaryBody({ data }: { data: EmployeeMonthSummary }) {
  const gross = data.timeComposition.workedSeconds + data.timeComposition.breakSeconds;
  const noData = gross === 0 && data.daysPresent === 0 && data.leaveDaysTotal === 0;
  if (noData) {
    return (
      <EmptyState
        icon={CalendarClock}
        title="No attendance this month"
        description="This employee has no sessions or leave recorded for the selected month."
      />
    );
  }
  const pct = (v: number) => (gross > 0 ? Math.round((v / gross) * 100) : 0);
  const pieData = [
    { name: 'Worked', value: data.timeComposition.workedSeconds, color: WORKED },
    { name: 'Break', value: data.timeComposition.breakSeconds, color: BREAK },
  ];

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      {/* Donut — worked vs break only (sums to gross clocked time). */}
      <Card className="lg:col-span-1">
        <CardHeader>
          <CardTitle className="text-base">Time composition</CardTitle>
        </CardHeader>
        <CardContent>
          {gross === 0 ? (
            <p className="py-8 text-center text-sm text-muted-foreground">No clocked time this month.</p>
          ) : (
            <>
              <div className="h-44">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={pieData}
                      dataKey="value"
                      nameKey="name"
                      innerRadius={52}
                      outerRadius={76}
                      paddingAngle={2}
                      strokeWidth={0}
                    >
                      {pieData.map((d) => (
                        <Cell key={d.name} fill={d.color} />
                      ))}
                    </Pie>
                    <Tooltip
                      formatter={(v, n) => [`${hm(Number(v))} · ${pct(Number(v))}%`, String(n)]}
                      contentStyle={{
                        borderRadius: 'var(--radius)',
                        border: '1px solid hsl(var(--border))',
                        background: 'hsl(var(--card))',
                        fontSize: 12,
                      }}
                    />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <div className="mt-2 space-y-1.5 text-sm">
                <LegendRow color={WORKED} label="Worked" value={`${hm(data.timeComposition.workedSeconds)} · ${pct(data.timeComposition.workedSeconds)}%`} />
                <LegendRow color={BREAK} label="Break" value={`${hm(data.timeComposition.breakSeconds)} · ${pct(data.timeComposition.breakSeconds)}%`} />
              </div>
            </>
          )}
        </CardContent>
      </Card>

      {/* Counts / other units — never in the donut. */}
      <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:col-span-2">
        <Stat label="Worked" value={hm(data.workedSeconds)} />
        <Stat label="Break time" value={hm(data.breakSeconds)} />
        <Stat label="Days present" value={String(data.daysPresent)} />
        <Stat label="Late logins" value={String(data.lateLogins)} tone={data.lateLogins > 0 ? 'warning' : undefined} />
        <Stat
          label="Adherence"
          value={data.adherencePct === null ? 'N/A' : `${data.adherencePct}%`}
          sub={
            data.adherencePct === null
              ? 'No expected working days'
              : `of ${data.expectedDays} expected · ${data.workingDays} working days`
          }
          title={data.workingDaysDefinition}
        />
        <Stat
          label="Unapproved Absences"
          value={String(data.unapprovedAbsences)}
          tone={data.unapprovedAbsences > 0 ? 'warning' : undefined}
          sub="Past working days, no session or leave"
          title={data.workingDaysDefinition}
        />
        <Stat
          label="Leaves taken"
          value={String(data.leaveDaysTotal)}
          sub={`C ${data.leavesByType.casual} · S ${data.leavesByType.sick} · U ${data.leavesByType.unpaid}`}
        />
      </div>
    </div>
  );
}

function LegendRow({ color, label, value }: { color: string; label: string; value: string }) {
  return (
    <div className="flex items-center justify-between">
      <span className="flex items-center gap-2 text-muted-foreground">
        <span className="size-2.5 rounded-full" style={{ background: color }} />
        {label}
      </span>
      <span className="font-medium tabular-nums">{value}</span>
    </div>
  );
}

function Stat({
  label,
  value,
  sub,
  tone,
  title,
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: 'warning';
  title?: string;
}) {
  return (
    <Card>
      <CardContent className="space-y-0.5 p-4" title={title}>
        <p className="text-xs text-muted-foreground">{label}</p>
        <p className={cn('text-xl font-semibold tabular-nums', tone === 'warning' && 'text-warning')}>
          {value}
        </p>
        {sub ? <p className="text-xs text-muted-foreground">{sub}</p> : null}
      </CardContent>
    </Card>
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
  activeMonth: string;
  onPickMonth: (m: string) => void;
}) {
  const chart = months.map((m) => ({
    month: monthLabel(m.month).replace(/ \d{4}$/, ''),
    key: m.month,
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
                <BarChart data={chart} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
                  <XAxis dataKey="month" tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 11, fill: 'hsl(var(--muted-foreground))' }} axisLine={false} tickLine={false} width={36} />
                  <Tooltip
                    formatter={(v) => [`${Number(v)}h`, 'Worked']}
                    contentStyle={{ borderRadius: 'var(--radius)', border: '1px solid hsl(var(--border))', background: 'hsl(var(--card))', fontSize: 12 }}
                  />
                  <Bar dataKey="workedHours" radius={[4, 4, 0, 0]}>
                    {chart.map((c) => (
                      <Cell key={c.key} fill={c.key === activeMonth ? WORKED : 'hsl(var(--primary) / 0.35)'} />
                    ))}
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-medium">Month</th>
                    <th className="px-3 py-2 font-medium">Worked</th>
                    <th className="px-3 py-2 font-medium">Days present</th>
                    <th className="px-3 py-2 font-medium">Late</th>
                    <th className="px-3 py-2 font-medium">Leave days</th>
                    <th className="px-3 py-2 font-medium">Absent</th>
                  </tr>
                </thead>
                <tbody>
                  {[...months].reverse().map((m) => (
                    <tr
                      key={m.month}
                      onClick={() => onPickMonth(m.month)}
                      className={cn(
                        'cursor-pointer border-t hover:bg-accent/40',
                        m.month === activeMonth && 'bg-primary/5',
                      )}
                    >
                      <td className="px-3 py-2 font-medium">{monthLabel(m.month)}</td>
                      <td className="px-3 py-2 tabular-nums">{hm(m.workedSeconds)}</td>
                      <td className="px-3 py-2 tabular-nums">{m.daysPresent}</td>
                      <td className="px-3 py-2 tabular-nums">{m.lateLogins}</td>
                      <td className="px-3 py-2 tabular-nums">{m.leaveDaysTotal}</td>
                      <td className={cn('px-3 py-2 tabular-nums', m.unapprovedAbsences > 0 && 'text-warning')}>
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
