'use client';

import * as React from 'react';
import { CalendarClock, Download, Users } from 'lucide-react';
import type { TeamAttendanceMemberRow, TeamAttendanceSummary } from '@/lib/contract';
import { getTeamAttendanceSummary } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { formatDuration, istMonthIso } from '@/lib/date';
import { downloadCsv, toCsv } from '@/lib/csv';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { MonthPicker } from '@/components/accountant/month-picker';
import { EmployeeAttendanceDetail } from '@/components/accountant/employee-attendance-detail';
import { cn } from '@/lib/utils';

const REFRESH_MS = 45_000;
const hm = (s: number) => formatDuration(s);
const hours = (s: number) => Math.round((s / 3600) * 100) / 100;

/** Export the loaded per-employee roster for the selected month as CSV (no refetch). */
function exportTeamCsv(data: TeamAttendanceSummary): void {
  const headers = [
    'Employee name',
    'Employee ID',
    'Clocked in now',
    'Worked (h)',
    'Late logins',
    'Leave days',
    'Unapproved absences',
  ];
  const rows = data.employees.map((e) => [
    e.fullName ?? '',
    e.employeeCode ?? '',
    e.clockedInNow ? 'Yes' : 'No',
    hours(e.workedSeconds),
    e.lateLogins,
    e.leaveDaysTotal,
    e.unapprovedAbsences,
  ]);
  downloadCsv(`attendance_team_${data.teamId}_${data.month}.csv`, toCsv(headers, rows));
}

/**
 * A team's attendance lens (§8a, read-only): a live roll-up + per-employee roster with a "clocked-in now"
 * dot, drilling into an employee's attendance detail. Scoped to {@code teamId} server-side (ACCOUNTANT
 * own-team-only). Live-on-load + ~45s polling + refetch-on-focus keep today's snapshot current.
 */
export function TeamAttendance({ teamId }: { teamId: string }) {
  const [month, setMonth] = React.useState(istMonthIso());
  const [selected, setSelected] = React.useState<TeamAttendanceMemberRow | null>(null);

  const query = useApiQuery(
    ['viewer-team-attendance', teamId, month],
    (signal) => getTeamAttendanceSummary(teamId, month, signal),
    {
      refetchOnMount: true,
      refetchOnWindowFocus: true,
      refetchInterval: REFRESH_MS,
      placeholderData: (prev) => prev,
    },
  );

  if (selected) {
    return (
      <EmployeeAttendanceDetail
        employeeId={selected.employeeId}
        employeeName={selected.fullName}
        month={month}
        onMonthChange={setMonth}
        onBack={() => setSelected(null)}
      />
    );
  }

  const data = query.data;
  const refreshing = query.isFetching && !query.isLoading;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-xs text-muted-foreground">
          {refreshing ? (
            <span className="inline-flex items-center gap-1.5">
              <span className="size-1.5 animate-pulse rounded-full bg-primary" />
              Refreshing…
            </span>
          ) : (
            'Live — updates automatically'
          )}
        </p>
        <div className="flex items-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={!data || data.employees.length === 0}
            onClick={() => data && exportTeamCsv(data)}
          >
            <Download className="size-4" />
            Export CSV
          </Button>
          <MonthPicker value={month} onChange={setMonth} />
        </div>
      </div>

      {query.isLoading ? (
        <TableSkeleton rows={6} cols={7} />
      ) : query.isError ? (
        <EmptyState
          icon={CalendarClock}
          title="Couldn't load team attendance"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : data ? (
        <div className={cn('space-y-4 transition-opacity', refreshing && 'opacity-70')}>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Tile label="Present today" value={data.presentToday} />
            <Tile label="Clocked in now" value={data.clockedInNow} tone="success" live />
            <Tile label="On leave today" value={data.onLeaveToday} />
            <Tile label="Late this month" value={data.totalLateThisMonth} tone={data.totalLateThisMonth > 0 ? 'warning' : undefined} />
          </div>

          {data.employees.length === 0 ? (
            <EmptyState
              icon={Users}
              title="No employees"
              description="This team has no approved employees yet."
            />
          ) : (
            <div className="overflow-x-auto rounded-md border">
              <table className="w-full text-sm">
                <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                  <tr>
                    <th className="px-3 py-2 font-medium">Employee</th>
                    <th className="px-3 py-2 font-medium">Employee ID</th>
                    <th className="px-3 py-2 font-medium">Now</th>
                    <th className="px-3 py-2 font-medium">Worked (month)</th>
                    <th className="px-3 py-2 font-medium">Late</th>
                    <th className="px-3 py-2 font-medium">Leave days</th>
                    <th className="px-3 py-2 font-medium">Absent</th>
                  </tr>
                </thead>
                <tbody>
                  {data.employees.map((e) => (
                    <tr
                      key={e.employeeId}
                      onClick={() => setSelected(e)}
                      className="cursor-pointer border-t hover:bg-accent/40"
                    >
                      <td className="px-3 py-2">
                        <span className="font-medium text-foreground hover:text-primary hover:underline">
                          {e.fullName ?? '—'}
                        </span>
                      </td>
                      <td className="px-3 py-2 font-mono text-xs text-muted-foreground">
                        {e.employeeCode ?? '—'}
                      </td>
                      <td className="px-3 py-2">
                        {e.clockedInNow ? (
                          <span className="inline-flex items-center gap-1.5 text-success">
                            <span className="size-2 rounded-full bg-success" />
                            In
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1.5 text-muted-foreground">
                            <span className="size-2 rounded-full bg-muted-foreground/40" />
                            Out
                          </span>
                        )}
                      </td>
                      <td className="px-3 py-2 tabular-nums">{hm(e.workedSeconds)}</td>
                      <td className={cn('px-3 py-2 tabular-nums', e.lateLogins > 0 && 'text-warning')}>
                        {e.lateLogins}
                      </td>
                      <td className="px-3 py-2 tabular-nums">{e.leaveDaysTotal}</td>
                      <td className={cn('px-3 py-2 tabular-nums', e.unapprovedAbsences > 0 && 'text-warning')}>
                        {e.unapprovedAbsences}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      ) : null}
    </div>
  );
}

function Tile({
  label,
  value,
  tone,
  live,
}: {
  label: string;
  value: number;
  tone?: 'success' | 'warning';
  live?: boolean;
}) {
  return (
    <Card>
      <CardContent className="p-4">
        <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
          {live ? <span className="size-1.5 rounded-full bg-success" /> : null}
          {label}
        </p>
        <p
          className={cn(
            'mt-0.5 text-2xl font-semibold tabular-nums',
            tone === 'success' && 'text-success',
            tone === 'warning' && 'text-warning',
          )}
        >
          {value}
        </p>
      </CardContent>
    </Card>
  );
}
