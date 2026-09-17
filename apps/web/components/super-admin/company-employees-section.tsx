'use client';

import * as React from 'react';
import Link from 'next/link';
import { UserRound } from 'lucide-react';
import type { EmployeeStatus } from '@/lib/contract';
import { getCompanyEmployees } from '@/lib/api/employees';
import { useApiQuery } from '@/lib/api/hooks';
import { companyParam } from '@/lib/company-url';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { TableSkeleton } from '@/components/loading-skeleton';
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
  'h-10 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';
const PAGE_SIZE = 20;

/** The Super Admin's view of a company's employees (all teams) — open a record to review or edit Form 2. */
export function CompanyEmployeesSection({ companyId, companySlug }: { companyId: string; companySlug: string }) {
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

  const query = useApiQuery(
    ['company-employees', companyId, debounced, status, page],
    (signal) => getCompanyEmployees(companyId, { search: debounced, status, page, size: PAGE_SIZE }, signal),
    { placeholderData: (prev) => prev },
  );
  const data = query.data;
  const dim = query.isFetching && query.isPlaceholderData;

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2 text-base">
          <UserRound className="size-4 text-muted-foreground" />
          Employees
        </CardTitle>
        <CardDescription>
          Every employee across this company&apos;s teams. Open one to view their forms or edit Employee
          Info (Form 2) while they&apos;re still invited.
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="flex flex-wrap items-end gap-3">
          <div className="min-w-[12rem] flex-1 space-y-1.5">
            <label htmlFor="sa-emp-search" className="text-xs font-medium text-muted-foreground">
              Search
            </label>
            <Input
              id="sa-emp-search"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="Name, email or ID…"
            />
          </div>
          <div className="space-y-1.5">
            <label htmlFor="sa-emp-status" className="text-xs font-medium text-muted-foreground">
              Status
            </label>
            <select
              id="sa-emp-status"
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
          <TableSkeleton rows={5} cols={5} />
        ) : query.isError ? (
          <EmptyState
            icon={UserRound}
            title="Couldn't load employees"
            description={query.error?.message ?? 'Please try again.'}
          />
        ) : !data || data.content.length === 0 ? (
          <EmptyState
            icon={UserRound}
            title="No employees found"
            description="Onboard an employee into a team, or adjust the search and status filters."
          />
        ) : (
          <div className={cn('overflow-x-auto rounded-xl border transition-opacity', dim && 'opacity-60')}>
            <table className="w-full text-sm">
              <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Name</th>
                  <th className="px-4 py-3 font-medium">Email</th>
                  <th className="px-4 py-3 font-medium">Designation</th>
                  <th className="px-4 py-3 font-medium">Status</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody>
                {data.content.map((e) => (
                  <tr key={e.id} className="border-t hover:bg-accent/40">
                    <td className="px-4 py-3 font-medium">
                      {e.fullName ?? '—'}
                      {/* Onboarded as an existing employee (§3.2) — no offer letter in their flow. */}
                      {e.onboardingType === 'EXISTING_EMPLOYEE' ? (
                        <Badge variant="neutral" className="ml-2 align-middle">
                          Existing
                        </Badge>
                      ) : null}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground">{e.email}</td>
                    <td className="px-4 py-3 text-muted-foreground">{e.designation ?? '—'}</td>
                    <td className="px-4 py-3">
                      <StatusBadge status={e.status} />
                    </td>
                    <td className="whitespace-nowrap px-4 py-3 text-right">
                      <Link
                        href={`/super-admin/companies/${companyParam(companySlug, companyId)}/employees/${e.id}`}
                        className="inline-flex min-h-11 items-center px-2 text-sm font-medium text-primary hover:underline"
                      >
                        Open
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
      </CardContent>
    </Card>
  );
}
