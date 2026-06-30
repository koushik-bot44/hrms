'use client';

import * as React from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { ChevronLeft, ChevronRight, ScrollText, X } from 'lucide-react';
import type { AuditLogEntry } from '@/lib/contract';
import { listCompanies } from '@/lib/api/companies';
import { getAuditLogs } from '@/lib/api/audit';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 25;
const SELECT_CLASS =
  'h-10 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

const COLUMNS: ColumnDef<AuditLogEntry>[] = [
  {
    accessorKey: 'actorLabel',
    header: 'Actor',
    cell: ({ row }) => (
      <div className="min-w-0">
        <div className="truncate font-medium">{row.original.actorLabel}</div>
        <div className="text-xs text-muted-foreground">{row.original.actorType}</div>
      </div>
    ),
  },
  {
    accessorKey: 'action',
    header: 'Action',
    cell: ({ row }) => <span className="font-mono text-xs">{row.original.action}</span>,
  },
  {
    accessorKey: 'targetType',
    header: 'Target',
    cell: ({ row }) =>
      row.original.targetType ? (
        <span className="text-sm">
          {row.original.targetType}
          {row.original.targetId ? (
            <span className="text-muted-foreground"> ·{row.original.targetId.slice(-6)}</span>
          ) : null}
        </span>
      ) : (
        <span className="text-muted-foreground">—</span>
      ),
  },
  {
    accessorKey: 'createdAt',
    header: 'Time',
    cell: ({ row }) => (
      <span className="whitespace-nowrap text-sm text-muted-foreground" title={row.original.createdAt}>
        {new Date(row.original.createdAt).toLocaleString()}
      </span>
    ),
  },
];

export function AuditExplorer({ scope }: { scope: 'super' | 'company' }) {
  const [companyId, setCompanyId] = React.useState('');
  const [action, setAction] = React.useState('');
  const [actorType, setActorType] = React.useState('');
  const [from, setFrom] = React.useState('');
  const [to, setTo] = React.useState('');
  const [page, setPage] = React.useState(0);

  const companiesQuery = useApiQuery(['companies'], listCompanies, { enabled: scope === 'super' });

  const ready = scope === 'company' || Boolean(companyId);
  const auditQuery = useApiQuery(
    ['audit', scope, companyId, action, actorType, from, to, page],
    (signal) =>
      getAuditLogs(
        {
          companyId: scope === 'super' ? companyId : undefined,
          action: action || undefined,
          actorType: actorType || undefined,
          from: from ? `${from}T00:00:00Z` : undefined,
          to: to ? `${to}T23:59:59Z` : undefined,
          page,
          size: PAGE_SIZE,
        },
        signal,
      ),
    { enabled: ready, retry: false, placeholderData: (prev) => prev },
  );

  function onFilter<T>(setter: (v: T) => void) {
    return (v: T) => {
      setter(v);
      setPage(0);
    };
  }
  function clearFilters() {
    setAction('');
    setActorType('');
    setFrom('');
    setTo('');
    setPage(0);
  }

  const hasFilters = Boolean(action || actorType || from || to);
  const data = auditQuery.data;

  return (
    <div className="space-y-4">
      {scope === 'super' ? (
        <div className="flex flex-col gap-1.5">
          <label htmlFor="audit-company" className="text-sm font-medium">
            Company
          </label>
          <select
            id="audit-company"
            value={companyId}
            onChange={(e) => onFilter(setCompanyId)(e.target.value)}
            className={cn(SELECT_CLASS, 'w-full max-w-sm')}
          >
            <option value="">Select a company…</option>
            {companiesQuery.data?.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name} ({c.code})
              </option>
            ))}
          </select>
        </div>
      ) : null}

      {!ready ? (
        <EmptyState
          icon={ScrollText}
          title="Pick a company"
          description="Choose a company to view its audit trail, kept separate from every other company."
        />
      ) : (
        <>
          <div className="flex flex-wrap items-end gap-3">
            <div className="flex flex-col gap-1.5">
              <label htmlFor="audit-action" className="text-xs font-medium text-muted-foreground">
                Action
              </label>
              <Input
                id="audit-action"
                value={action}
                onChange={(e) => onFilter(setAction)(e.target.value)}
                placeholder="e.g. APPROVAL"
                className="w-44"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="audit-actor" className="text-xs font-medium text-muted-foreground">
                Actor
              </label>
              <select
                id="audit-actor"
                value={actorType}
                onChange={(e) => onFilter(setActorType)(e.target.value)}
                className={SELECT_CLASS}
              >
                <option value="">All actors</option>
                <option value="USER">Staff</option>
                <option value="EMPLOYEE">Employee</option>
                <option value="SYSTEM">System</option>
              </select>
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="audit-from" className="text-xs font-medium text-muted-foreground">
                From
              </label>
              <Input
                id="audit-from"
                type="date"
                value={from}
                onChange={(e) => onFilter(setFrom)(e.target.value)}
                className="w-40"
              />
            </div>
            <div className="flex flex-col gap-1.5">
              <label htmlFor="audit-to" className="text-xs font-medium text-muted-foreground">
                To
              </label>
              <Input
                id="audit-to"
                type="date"
                value={to}
                onChange={(e) => onFilter(setTo)(e.target.value)}
                className="w-40"
              />
            </div>
            {hasFilters ? (
              <Button type="button" variant="ghost" size="sm" onClick={clearFilters}>
                <X />
                Clear
              </Button>
            ) : null}
          </div>

          {auditQuery.isLoading ? (
            <TableSkeleton rows={8} />
          ) : auditQuery.isError ? (
            <EmptyState
              icon={ScrollText}
              title="Couldn't load the audit trail"
              description={auditQuery.error?.message ?? 'Please try again.'}
            />
          ) : (
            <div
              className={cn(
                'transition-opacity',
                auditQuery.isFetching && auditQuery.isPlaceholderData ? 'opacity-60' : 'opacity-100',
              )}
            >
              <DataTable
                columns={COLUMNS}
                data={data?.content ?? []}
                searchPlaceholder="Filter loaded rows…"
                emptyState={
                  <EmptyState
                    icon={ScrollText}
                    title="No matching events"
                    description="Nothing logged for these filters yet."
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
        </>
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
        Page {page + 1} of {Math.max(1, totalPages)} · {totalElements} event
        {totalElements === 1 ? '' : 's'}
      </span>
      <div className="flex items-center gap-2">
        <Button type="button" variant="outline" size="sm" disabled={page <= 0} onClick={onPrev}>
          <ChevronLeft />
          Previous
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={page + 1 >= totalPages}
          onClick={onNext}
        >
          Next
          <ChevronRight />
        </Button>
      </div>
    </div>
  );
}
