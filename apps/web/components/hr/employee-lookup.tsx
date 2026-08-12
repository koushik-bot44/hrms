'use client';

import * as React from 'react';
import { ArrowLeft, Search, ShieldCheck } from 'lucide-react';
import { getEmployeeQueue } from '@/lib/api/employees';
import { getEmployeeRecord } from '@/lib/api/review';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';
import { RecordView } from '@/components/hr/record-view';
import { cn } from '@/lib/utils';

/**
 * §3.4 records lookup — read-only, approved employees only. Search by employee ID or name (the
 * queue is filtered to APPROVED); pick a match to view the full record.
 */
export function EmployeeLookup() {
  const [search, setSearch] = React.useState('');
  const [debounced, setDebounced] = React.useState('');
  const [selectedId, setSelectedId] = React.useState<string | null>(null);

  React.useEffect(() => {
    const t = setTimeout(() => setDebounced(search.trim()), 300);
    return () => clearTimeout(t);
  }, [search]);

  const results = useApiQuery(
    ['hr-lookup-search', debounced],
    (signal) => getEmployeeQueue({ search: debounced, status: 'APPROVED', size: 20 }, signal),
    { enabled: debounced.length > 0, placeholderData: (prev) => prev },
  );

  const record = useApiQuery(
    selectedId ? ['hr-lookup-record', selectedId] : ['hr-lookup-record', 'idle'],
    (signal) => getEmployeeRecord(selectedId as string, signal),
    { enabled: Boolean(selectedId), retry: false },
  );

  // Detail view: a match was picked — show the full record (read-only).
  if (selectedId) {
    return (
      <div className="space-y-6">
        <Button type="button" variant="outline" size="sm" onClick={() => setSelectedId(null)}>
          <ArrowLeft />
          Back to results
        </Button>

        {record.isLoading ? <Skeleton className="h-40 w-full" /> : null}

        {record.isError ? (
          <EmptyState
            icon={ShieldCheck}
            title="Couldn't load the record"
            description={
              record.error?.status === 404
                ? 'That employee is no longer in your workspace.'
                : record.error?.message ?? 'Please try again.'
            }
          />
        ) : null}

        {record.data ? <RecordView record={record.data} editable={false} /> : null}
      </div>
    );
  }

  const data = results.data;
  const dim = results.isFetching && results.isPlaceholderData;

  return (
    <div className="space-y-6">
      <div className="space-y-1.5">
        <label htmlFor="lookup" className="sr-only">
          Search by employee ID or name
        </label>
        <div className="relative">
          <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground" />
          <Input
            id="lookup"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search by employee ID or name, e.g. ACME-EMP-000123 or Priya"
            className="pl-9"
            autoComplete="off"
          />
        </div>
      </div>

      {debounced.length === 0 ? (
        <EmptyState
          icon={ShieldCheck}
          title="Look up an approved employee"
          description="Search by employee ID or name to view their full record (read-only). IDs are assigned on approval."
        />
      ) : results.isLoading ? (
        <div className="space-y-2">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </div>
      ) : results.isError ? (
        <EmptyState
          icon={ShieldCheck}
          title="Couldn't search employees"
          description={results.error?.message ?? 'Please try again.'}
        />
      ) : !data || data.content.length === 0 ? (
        <EmptyState
          icon={ShieldCheck}
          title="No matching employee"
          description="No approved employee in your workspace matches that ID or name."
        />
      ) : (
        <div className={cn('overflow-x-auto rounded-md border transition-opacity', dim && 'opacity-60')}>
          <table className="w-full text-sm">
            <thead className="bg-muted/40 text-left text-xs text-muted-foreground">
              <tr>
                <th className="px-3 py-2 font-medium">Name</th>
                <th className="px-3 py-2 font-medium">Employee ID</th>
                <th className="px-3 py-2 font-medium">Designation</th>
                <th className="px-3 py-2 font-medium">Status</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {data.content.map((e) => (
                <tr key={e.id} className="border-t hover:bg-accent/40">
                  <td className="px-3 py-2 font-medium">{e.fullName ?? '—'}</td>
                  <td className="break-words px-3 py-2 font-mono text-muted-foreground">{e.employeeCode ?? '—'}</td>
                  <td className="px-3 py-2 text-muted-foreground">{e.designation ?? '—'}</td>
                  <td className="px-3 py-2">
                    <StatusBadge status={e.status} />
                  </td>
                  <td className="whitespace-nowrap px-3 py-2 text-right">
                    <Button
                      type="button"
                      variant="link"
                      size="sm"
                      className="px-2"
                      onClick={() => setSelectedId(e.id)}
                    >
                      View record
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
