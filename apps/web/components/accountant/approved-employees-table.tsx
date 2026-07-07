'use client';

import * as React from 'react';
import Link from 'next/link';
import type { ColumnDef } from '@tanstack/react-table';
import { ChevronLeft, ChevronRight, Users } from 'lucide-react';
import type { ApprovedEmployeeRow } from '@/lib/contract';
import { UserRole } from '@/lib/contract';
import { getApprovedEmployees } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { useAuth } from '@/components/auth-provider';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 20;
const SELECT_CLASS =
  'h-10 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

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
  { accessorKey: 'companyName', header: 'Company', cell: ({ row }) => row.original.companyName ?? '—' },
  {
    accessorKey: 'designation',
    header: 'Designation',
    cell: ({ row }) => row.original.designation ?? '—',
  },
  {
    accessorKey: 'dateOfJoining',
    header: 'Joining',
    cell: ({ row }) => row.original.dateOfJoining ?? '—',
  },
  {
    id: 'view',
    header: '',
    enableSorting: false,
    cell: ({ row }) => (
      <div className="text-right">
        <Link href={`/accountant/employees/${row.original.id}`} className="text-sm font-medium text-primary hover:underline">
          View record
        </Link>
      </div>
    ),
  },
];

/**
 * Approved employees (§2): server search + pagination. The company filter/column only makes sense for
 * the cross-company Accounts Admin — the team Accountant has a single company, so both are hidden.
 */
export function ApprovedEmployeesTable() {
  const { session } = useAuth();
  const crossCompany = session?.type === 'USER' && session.role === UserRole.ACCOUNTS_ADMIN;

  const [search, setSearch] = React.useState('');
  const [debounced, setDebounced] = React.useState('');
  const [companyId, setCompanyId] = React.useState('');
  const [page, setPage] = React.useState(0);
  // The Accounts Admin can't list companies (SUPER_ADMIN-only), so the filter is built from the
  // companies seen in the approved rows — accumulated so a selection never empties the dropdown.
  const [companies, setCompanies] = React.useState<Map<string, string>>(new Map());

  // Drop the redundant Company column for the single-company team Accountant.
  const columns = React.useMemo(
    () =>
      crossCompany
        ? COLUMNS
        : COLUMNS.filter((c) => (c as { accessorKey?: string }).accessorKey !== 'companyName'),
    [crossCompany],
  );

  React.useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const query = useApiQuery(
    ['accountant-approved', debounced, companyId, page],
    (signal) =>
      getApprovedEmployees({ search: debounced, companyId: companyId || undefined, page, size: PAGE_SIZE }, signal),
    { placeholderData: (prev) => prev },
  );

  const data = query.data;
  React.useEffect(() => {
    if (!data || !crossCompany) return;
    setCompanies((prev) => {
      const next = new Map(prev);
      for (const r of data.content) {
        if (r.companyId && r.companyName) next.set(r.companyId, r.companyName);
      }
      return next.size === prev.size ? prev : next;
    });
  }, [data]);

  const dim = query.isFetching && query.isPlaceholderData;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[14rem] flex-1 space-y-1.5">
          <label htmlFor="acc-search" className="text-xs font-medium text-muted-foreground">
            Search
          </label>
          <Input
            id="acc-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Name, email or employee ID…"
          />
        </div>
        {crossCompany ? (
          <div className="space-y-1.5">
            <label htmlFor="acc-company" className="text-xs font-medium text-muted-foreground">
              Company
            </label>
            <select
              id="acc-company"
              value={companyId}
              onChange={(e) => {
                setCompanyId(e.target.value);
                setPage(0);
              }}
              className={SELECT_CLASS}
            >
              <option value="">All companies</option>
              {[...companies.entries()].map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
          </div>
        ) : null}
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
          <DataTable
            columns={columns}
            data={data?.content ?? []}
            searchPlaceholder="Filter loaded rows…"
            emptyState={
              <EmptyState
                icon={Users}
                title="No approved employees"
                description="Approved employees across all companies will appear here."
              />
            }
          />
          <Pagination
            page={data?.page ?? 0}
            totalPages={data?.totalPages ?? 0}
            totalElements={data?.totalElements ?? 0}
            onPrev={() => setPage((p) => Math.max(0, p - 1))}
            onNext={() => setPage((p) => p + 1)}
          />
        </div>
      )}
    </div>
  );
}

function Pagination({
  page,
  totalPages,
  totalElements,
  onPrev,
  onNext,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  onPrev: () => void;
  onNext: () => void;
}) {
  if (totalElements === 0) return null;
  return (
    <div className="flex items-center justify-between pt-3 text-sm text-muted-foreground">
      <span>
        Page {page + 1} of {Math.max(1, totalPages)} · {totalElements} employee
        {totalElements === 1 ? '' : 's'}
      </span>
      <div className="flex items-center gap-2">
        <Button type="button" variant="outline" size="sm" disabled={page <= 0} onClick={onPrev}>
          <ChevronLeft />
          Previous
        </Button>
        <Button type="button" variant="outline" size="sm" disabled={page + 1 >= totalPages} onClick={onNext}>
          Next
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}
