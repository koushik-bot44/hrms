'use client';

import * as React from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { Users, X } from 'lucide-react';
import type { TeamAttendanceRow } from '@/lib/contract';
import { attendanceKeys, getTeamEmployeeAttendance, getTeamSummary } from '@/lib/api/attendance';
import { useApiQuery } from '@/lib/api/hooks';
import { formatDuration, istDaysAgoIso, istTodayIso } from '@/lib/date';
import { DataTable } from '@/components/data-table';
import { AttendanceHistory } from '@/components/attendance/attendance-history';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';

/**
 * Manager attendance roster (§8a): the manager's team-scoped employees with their today/period totals and
 * live clocked-in state. Scope is enforced server-side (same team set the manager approves). Clicking a
 * row drills into that employee's day-grouped history. View-only.
 */
export function TeamAttendance() {
  const [from, setFrom] = React.useState(() => istDaysAgoIso(13));
  const [to, setTo] = React.useState(() => istTodayIso());
  const [selected, setSelected] = React.useState<TeamAttendanceRow | null>(null);

  const query = useApiQuery(
    attendanceKeys.teamSummary(from, to),
    (signal) => getTeamSummary(from, to, signal),
    { placeholderData: (prev) => prev },
  );

  const columns = React.useMemo<ColumnDef<TeamAttendanceRow>[]>(
    () => [
      {
        accessorKey: 'fullName',
        header: 'Employee',
        cell: ({ row }) => (
          <button
            type="button"
            onClick={() => setSelected(row.original)}
            className="text-left font-medium text-foreground hover:text-primary hover:underline"
          >
            {row.original.fullName ?? '—'}
          </button>
        ),
      },
      {
        accessorKey: 'employeeCode',
        header: 'Employee ID',
        cell: ({ row }) => (
          <span className="font-mono text-xs">{row.original.employeeCode ?? '—'}</span>
        ),
      },
      {
        accessorKey: 'clockedIn',
        header: 'Now',
        cell: ({ row }) =>
          row.original.clockedIn ? (
            <Badge variant="success">Clocked in</Badge>
          ) : (
            <Badge variant="neutral">Out</Badge>
          ),
      },
      {
        accessorKey: 'todaySeconds',
        header: 'Today',
        cell: ({ row }) => (
          <span className="tabular-nums">{formatDuration(row.original.todaySeconds)}</span>
        ),
      },
      {
        accessorKey: 'periodSeconds',
        header: 'Range total',
        cell: ({ row }) => (
          <span className="tabular-nums">{formatDuration(row.original.periodSeconds)}</span>
        ),
      },
    ],
    [],
  );

  const rangeToolbar = (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <label className="flex items-center gap-1.5">
        <span className="text-xs text-muted-foreground">From</span>
        <Input
          type="date"
          value={from}
          max={to}
          onChange={(e) => setFrom(e.target.value)}
          className="h-9 w-auto"
        />
      </label>
      <label className="flex items-center gap-1.5">
        <span className="text-xs text-muted-foreground">To</span>
        <Input
          type="date"
          value={to}
          min={from}
          max={istTodayIso()}
          onChange={(e) => setTo(e.target.value)}
          className="h-9 w-auto"
        />
      </label>
    </div>
  );

  return (
    <div className="space-y-6">
      <Card>
        <CardHeader>
          <CardTitle className="text-base">Team attendance</CardTitle>
        </CardHeader>
        <CardContent>
          {query.isLoading ? (
            <TableSkeleton rows={5} cols={5} />
          ) : query.isError ? (
            <EmptyState
              icon={Users}
              title="Couldn't load attendance"
              description={query.error?.message ?? 'Please try again.'}
            />
          ) : (
            <DataTable
              columns={columns}
              data={query.data ?? []}
              searchPlaceholder="Filter by name or ID…"
              toolbar={rangeToolbar}
              emptyState={
                <EmptyState
                  icon={Users}
                  title="No team members"
                  description="Employees you onboard and approve will appear here."
                />
              }
            />
          )}
        </CardContent>
      </Card>

      {selected ? (
        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <div>
              <h2 className="text-sm font-semibold">{selected.fullName ?? 'Employee'}</h2>
              <p className="font-mono text-xs text-muted-foreground">{selected.employeeCode ?? '—'}</p>
            </div>
            <Button variant="ghost" size="sm" onClick={() => setSelected(null)}>
              <X />
              Close
            </Button>
          </div>
          <AttendanceHistory
            key={selected.employeeId}
            title="Sessions"
            fetchPage={(f, t, page, signal) =>
              getTeamEmployeeAttendance(selected.employeeId, f, t, page, 50, signal)
            }
            queryKey={(f, t, page) => attendanceKeys.teamEmployee(selected.employeeId, f, t, page)}
          />
        </div>
      ) : null}
    </div>
  );
}
