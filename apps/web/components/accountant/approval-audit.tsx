'use client';

import * as React from 'react';
import type { ColumnDef } from '@tanstack/react-table';
import { ChevronLeft, ChevronRight, ScrollText } from 'lucide-react';
import type { AuditLogEntry } from '@/lib/contract';
import { getApprovalAudit } from '@/lib/api/accountant';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { DataTable } from '@/components/data-table';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { cn } from '@/lib/utils';

const PAGE_SIZE = 25;

const COLUMNS: ColumnDef<AuditLogEntry>[] = [
  {
    accessorKey: 'action',
    header: 'Event',
    cell: ({ row }) => <span className="font-mono text-xs">{row.original.action}</span>,
  },
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
    accessorKey: 'companyName',
    header: 'Company',
    cell: ({ row }) => row.original.companyName ?? '—',
  },
  {
    accessorKey: 'targetLabel',
    header: 'Employee',
    cell: ({ row }) => row.original.targetLabel ?? '—',
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

/** Cross-company, approval-only audit trail (§7) — the only audit the Accountant may read. */
export function ApprovalAudit() {
  const [page, setPage] = React.useState(0);
  const query = useApiQuery(
    ['accountant-audit', page],
    (signal) => getApprovalAudit({ page, size: PAGE_SIZE }, signal),
    { placeholderData: (prev) => prev, retry: false },
  );
  const data = query.data;
  const dim = query.isFetching && query.isPlaceholderData;

  if (query.isLoading) return <TableSkeleton rows={8} />;
  if (query.isError) {
    return (
      <EmptyState
        icon={ScrollText}
        title="Couldn't load the approval trail"
        description={query.error?.message ?? 'Please try again.'}
      />
    );
  }

  return (
    <div className={cn('space-y-3 transition-opacity', dim && 'opacity-60')}>
      <DataTable carded
        columns={COLUMNS}
        data={data?.content ?? []}
        searchPlaceholder="Filter loaded rows…"
        emptyState={
          <EmptyState
            icon={ScrollText}
            title="No approval events yet"
            description="Approval decisions across all companies will appear here."
          />
        }
      />
      {data && data.totalElements > 0 ? (
        <div className="flex items-center justify-between pt-1 text-sm text-muted-foreground">
          <span>
            Page {data.page + 1} of {Math.max(1, data.totalPages)} · {data.totalElements} event
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
              <ChevronLeft />
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
              <ChevronRight />
            </Button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
