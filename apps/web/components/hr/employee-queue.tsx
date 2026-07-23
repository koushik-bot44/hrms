'use client';

import * as React from 'react';
import Link from 'next/link';
import { Users } from 'lucide-react';
import type { EmployeeStatus } from '@/lib/contract';
import { getEmployeeQueue } from '@/lib/api/employees';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';
import { cn } from '@/lib/utils';

const STATUSES: EmployeeStatus[] = [
  'INVITED',
  'IN_PROGRESS',
  'SUBMITTED',
  'REVISION_REQUESTED',
  'HR_VERIFIED',
  'APPROVED',
  'REJECTED',
];
const SELECT_CLASS =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';
const PAGE_SIZE = 20;

/** The HR's onboarding queue: their employees with name/email search + status filter, server-paginated. */
export function EmployeeQueue() {
  const [search, setSearch] = React.useState('');
  const [debounced, setDebounced] = React.useState('');
  const [status, setStatus] = React.useState<EmployeeStatus | ''>('');
  const [page, setPage] = React.useState(0);

  React.useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(search);
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  // Drill-down from the dashboard: ?status=SUBMITTED pre-filters the queue.
  React.useEffect(() => {
    const s = new URLSearchParams(window.location.search).get('status');
    if (s && (STATUSES as string[]).includes(s)) {
      setStatus(s as EmployeeStatus);
      setPage(0);
    }
  }, []);

  const query = useApiQuery(
    ['hr-employees', debounced, status, page],
    (signal) => getEmployeeQueue({ search: debounced, status, page, size: PAGE_SIZE }, signal),
    { placeholderData: (prev) => prev },
  );
  const data = query.data;
  const dim = query.isFetching && query.isPlaceholderData;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end gap-3">
        <div className="min-w-[12rem] flex-1 space-y-1.5">
          <label htmlFor="emp-search" className="text-xs font-medium text-muted-foreground">
            Search
          </label>
          <Input
            id="emp-search"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Name or email…"
          />
        </div>
        <div className="space-y-1.5">
          <label htmlFor="emp-status" className="text-xs font-medium text-muted-foreground">
            Status
          </label>
          <select
            id="emp-status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as EmployeeStatus | '');
              setPage(0);
            }}
            className={SELECT_CLASS}
          >
            <option value="">All statuses</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </div>
      </div>

      {query.isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : query.isError ? (
        <EmptyState
          icon={Users}
          title="Couldn't load employees"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : !data || data.content.length === 0 ? (
        <EmptyState
          icon={Users}
          title="No employees found"
          description="Onboard an employee, or adjust the search and status filters."
        />
      ) : (
        <div className={cn('overflow-x-auto rounded-2xl border bg-card shadow-card transition-opacity', dim && 'opacity-60')}>
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Email</th>
                <th className="px-4 py-3 font-medium">Designation</th>
                <th className="px-4 py-3 font-medium">Joining</th>
                <th className="px-4 py-3 font-medium">Status</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody>
              {data.content.map((e) => (
                <tr key={e.id} className="border-t hover:bg-accent/40">
                  <td className="px-4 py-3 font-medium">{e.fullName ?? '—'}</td>
                  <td className="px-4 py-3 text-muted-foreground">{e.email}</td>
                  <td className="px-4 py-3 text-muted-foreground">{e.designation ?? '—'}</td>
                  <td className="px-4 py-3 text-muted-foreground">{e.dateOfJoining ?? '—'}</td>
                  <td className="px-4 py-3">
                    <StatusBadge status={e.status} />
                  </td>
                  <td className="whitespace-nowrap px-4 py-3 text-right">
                    <Link href={`/hr/employees/${e.id}`} className="text-sm font-medium text-primary hover:underline">
                      {e.status === 'SUBMITTED' ? 'Review' : 'Open'}
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.totalElements > 0 ? (
        <div className="flex items-center justify-between pt-1 text-sm text-muted-foreground">
          <span>
            Page {data.page + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} employee
            {data.totalElements === 1 ? '' : 's'}
          </span>
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={data.page <= 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              Previous
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={data.page + 1 >= data.totalPages}
              onClick={() => setPage((p) => p + 1)}
            >
              Next
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
