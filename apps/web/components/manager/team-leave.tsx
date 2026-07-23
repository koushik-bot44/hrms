'use client';

import * as React from 'react';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import type { ColumnDef } from '@tanstack/react-table';
import { CalendarDays, Check, X } from 'lucide-react';
import type { TeamLeaveRow } from '@/lib/contract';
import { LeaveStatus } from '@/lib/contract';
import { approveLeave, getTeamLeave, leaveKeys, rejectLeave } from '@/lib/api/leave';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { DataTable } from '@/components/data-table';
import { StatusBadge } from '@/components/status-badge';
import { EmptyState } from '@/components/empty-state';
import { TableSkeleton } from '@/components/loading-skeleton';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

const SELECT_CLASS =
  'h-11 rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';
const TYPE_LABELS: Record<string, string> = { CASUAL: 'Casual', SICK: 'Sick', UNPAID: 'Unpaid' };

function formatDate(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
}

/**
 * Manager leave queue (§8b): requests routed to this Manager (his team-scope), filterable by status/date.
 * Approve / Reject (reject requires a note) — the employee is emailed + sees the outcome in their history.
 */
export function TeamLeave() {
  const queryClient = useQueryClient();
  const [status, setStatus] = React.useState<string>(LeaveStatus.PENDING); // pending first
  const [from, setFrom] = React.useState('');
  const [to, setTo] = React.useState('');
  const [page, setPage] = React.useState(0);
  const [rejecting, setRejecting] = React.useState<TeamLeaveRow | null>(null);

  const query = useApiQuery(
    leaveKeys.team(status, from, to, page),
    (signal) => getTeamLeave({ status: status || undefined, from, to, page, size: 20 }, signal),
    { placeholderData: keepPreviousData },
  );
  const data = query.data;

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['leave', 'team'] });
  const approveMutation = useApiMutation((id: string) => approveLeave(id), {
    successMessage: 'Leave approved',
    onSettled: refresh,
  });
  const rejectMutation = useApiMutation(
    (vars: { id: string; note: string }) => rejectLeave(vars.id, vars.note),
    { successMessage: 'Leave rejected', onSuccess: () => setRejecting(null), onSettled: refresh },
  );
  const busy = approveMutation.isPending || rejectMutation.isPending;

  const columns = React.useMemo<ColumnDef<TeamLeaveRow>[]>(
    () => [
      {
        accessorKey: 'employeeName',
        header: 'Employee',
        cell: ({ row }) => (
          <div>
            <div className="font-medium">{row.original.employeeName ?? '—'}</div>
            <div className="font-mono text-xs text-muted-foreground">
              {row.original.employeeCode ?? '—'}
            </div>
          </div>
        ),
      },
      {
        id: 'dates',
        header: 'Dates',
        cell: ({ row }) => (
          <span className="whitespace-nowrap tabular-nums">
            {formatDate(row.original.startDate)} → {formatDate(row.original.endDate)}
          </span>
        ),
      },
      {
        accessorKey: 'leaveType',
        header: 'Type',
        cell: ({ row }) => TYPE_LABELS[row.original.leaveType] ?? row.original.leaveType,
      },
      {
        accessorKey: 'reason',
        header: 'Reason',
        cell: ({ row }) => (
          <span className="line-clamp-2 max-w-[18rem] text-muted-foreground">{row.original.reason}</span>
        ),
      },
      {
        accessorKey: 'status',
        header: 'Status',
        cell: ({ row }) => <StatusBadge status={row.original.status} />,
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) =>
          row.original.status === 'PENDING' ? (
            <div className="flex items-center justify-end gap-1.5">
              <Button
                variant="success"
                size="sm"
                disabled={busy}
                onClick={() => approveMutation.mutate(row.original.id)}
              >
                <Check />
                Approve
              </Button>
              <Button variant="outline" size="sm" disabled={busy} onClick={() => setRejecting(row.original)}>
                <X />
                Reject
              </Button>
            </div>
          ) : null,
      },
    ],
    [busy, approveMutation],
  );

  const toolbar = (
    <div className="flex flex-wrap items-center gap-2 text-sm">
      <select
        value={status}
        onChange={(e) => {
          setStatus(e.target.value);
          setPage(0);
        }}
        className={SELECT_CLASS}
        aria-label="Status filter"
      >
        <option value="">All statuses</option>
        {Object.values(LeaveStatus).map((s) => (
          <option key={s} value={s}>
            {s.charAt(0) + s.slice(1).toLowerCase()}
          </option>
        ))}
      </select>
      <label className="flex items-center gap-1.5">
        <span className="text-xs text-muted-foreground">From</span>
        <Input
          type="date"
          value={from}
          onChange={(e) => {
            setFrom(e.target.value);
            setPage(0);
          }}
          className="w-auto"
        />
      </label>
      <label className="flex items-center gap-1.5">
        <span className="text-xs text-muted-foreground">To</span>
        <Input
          type="date"
          value={to}
          onChange={(e) => {
            setTo(e.target.value);
            setPage(0);
          }}
          className="w-auto"
        />
      </label>
    </div>
  );

  return (
    <div className="space-y-3">
      {query.isLoading ? (
        <TableSkeleton rows={5} cols={6} />
      ) : query.isError ? (
        <EmptyState
          icon={CalendarDays}
          title="Couldn't load leave requests"
          description={query.error?.message ?? 'Please try again.'}
        />
      ) : (
        <>
          <DataTable carded
            columns={columns}
            data={data?.content ?? []}
            searchPlaceholder="Filter by name…"
            toolbar={toolbar}
            emptyState={
              <EmptyState
                icon={CalendarDays}
                title="No leave requests"
                description="Requests from your team will appear here."
              />
            }
          />
          {data && data.totalPages > 1 ? (
            <div className="flex items-center justify-between pt-1 text-sm text-muted-foreground">
              <span className="text-xs">
                Page {data.page + 1} of {data.totalPages} · {data.totalElements} request
                {data.totalElements === 1 ? '' : 's'}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={data.page === 0 || query.isFetching}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={data.page >= data.totalPages - 1 || query.isFetching}
                  onClick={() => setPage((p) => p + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          ) : null}
        </>
      )}

      <RejectDialog
        row={rejecting}
        pending={rejectMutation.isPending}
        onCancel={() => setRejecting(null)}
        onConfirm={(note) => rejecting && rejectMutation.mutate({ id: rejecting.id, note })}
      />
    </div>
  );
}

function RejectDialog({
  row,
  pending,
  onCancel,
  onConfirm,
}: {
  row: TeamLeaveRow | null;
  pending: boolean;
  onCancel: () => void;
  onConfirm: (note: string) => void;
}) {
  const [note, setNote] = React.useState('');
  React.useEffect(() => {
    if (row) setNote('');
  }, [row]);

  return (
    <Dialog open={Boolean(row)} onOpenChange={(o) => (!o ? onCancel() : undefined)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Reject leave request</DialogTitle>
          <DialogDescription>
            Rejecting {row?.employeeName ?? 'this employee'}&rsquo;s request. A note is required — it is
            emailed to them.
          </DialogDescription>
        </DialogHeader>
        <textarea
          rows={3}
          autoFocus
          value={note}
          onChange={(e) => setNote(e.target.value)}
          placeholder="Why is this being rejected?"
          className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        />
        <div className="flex justify-end gap-2 pt-2">
          <Button variant="ghost" onClick={onCancel} disabled={pending}>
            Cancel
          </Button>
          <Button
            variant="destructive"
            disabled={pending || note.trim().length === 0}
            onClick={() => onConfirm(note.trim())}
          >
            {pending ? 'Rejecting…' : 'Reject request'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
