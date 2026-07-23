'use client';

import * as React from 'react';
import Link from 'next/link';
import type { ColumnDef } from '@tanstack/react-table';
import { ChevronLeft, ChevronRight, Search, Users } from 'lucide-react';
import type { ApprovedEmployeeRow } from '@/lib/contract';
import { getTeamEmployees } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card } from '@/components/ui/card';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 20;

const COLUMNS: ColumnDef<ApprovedEmployeeRow>[] = [
  {
    accessorKey: 'fullName',
    header: 'Name',
    cell: ({ row }) => (
      <Link
        href={`/accountant/employees/${row.original.id}`}
        className="font-medium text-foreground hover:text-primary hover:underline"
      >
        {row.original.fullName ?? row.original.email}
      </Link>
    ),
  },
  {
    accessorKey: 'employeeCode',
    header: 'Employee ID',
    cell: ({ row }) => <span className="font-mono text-xs">{row.original.employeeCode ?? '—'}</span>,
  },
  { accessorKey: 'designation', header: 'Designation', cell: ({ row }) => row.original.designation ?? '—' },
  { accessorKey: 'dateOfJoining', header: 'Joining', cell: ({ row }) => row.original.dateOfJoining ?? '—' },
  {
    id: 'view',
    header: '',
    enableSorting: false,
    cell: ({ row }) => (
      <div className="text-right">
        <Link
          href={`/accountant/employees/${row.original.id}`}
          className="text-sm font-medium text-primary hover:underline"
        >
          View record
        </Link>
      </div>
    ),
  },
];

/**
 * A team's APPROVED employees (§2), read-only. Server search + pagination. Used by BOTH viewer roles —
 * the ACCOUNTS_ADMIN reaches it via the company→team drilldown, the ACCOUNTANT lands on their own team.
 * Scope is enforced server-side (an ACCOUNTANT can only load their own team; else 404).
 */
export function TeamEmployeesTable({ teamId }: { teamId: string }) {
  const [search, setSearch] = React.useState('');
  const [debounced, setDebounced] = React.useState('');
  const [page, setPage] = React.useState(0);

  React.useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const query = useApiQuery(
    ['accountant-team-employees', teamId, debounced, page],
    (signal) => getTeamEmployees(teamId, { search: debounced, page, size: PAGE_SIZE }, signal),
    { placeholderData: (prev) => prev },
  );

  const data = query.data;
  const dim = query.isFetching && query.isPlaceholderData;

  return (
    <div className="space-y-4">
      {/* ONE search field — server search across the whole team (name, email, employee ID). The former
          "filter loaded rows" client box is dropped: the server search already spans every row. */}
      <div className="relative w-full max-w-md">
        <Search className="pointer-events-none absolute left-3.5 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          id="team-emp-search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search by name, email or employee ID…"
          aria-label="Search employees"
          className="rounded-xl pl-10"
        />
      </div>

      {query.isLoading ? (
        <TableSkeleton rows={6} cols={5} />
      ) : query.isError ? (
        <EmptyState
          icon={Users}
          title="Couldn't load employees"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : (
        <div className={cn('transition-opacity', dim && 'opacity-60')}>
          <Card className="overflow-hidden">
            <DataTable
              columns={COLUMNS}
              data={data?.content ?? []}
              searchable={false}
              containerClassName=""
              emptyState={
                <EmptyState
                  icon={Users}
                  title="No approved employees"
                  description="This team has no approved employees yet."
                />
              }
            />
          </Card>
          {(data?.totalElements ?? 0) > 0 ? (
            <div className="flex items-center justify-between pt-3 text-sm text-muted-foreground">
              <span>
                Page {(data?.page ?? 0) + 1} of {Math.max(1, data?.totalPages ?? 0)} ·{' '}
                {data?.totalElements ?? 0} employee{(data?.totalElements ?? 0) === 1 ? '' : 's'}
              </span>
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={(data?.page ?? 0) <= 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  <ChevronLeft />
                  Previous
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={(data?.page ?? 0) + 1 >= (data?.totalPages ?? 0)}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                  <ChevronRight />
                </Button>
              </div>
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}
