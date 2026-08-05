'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { Building2, CalendarOff, CheckCircle2, XCircle } from 'lucide-react';
import type { HierarchyPendingRow } from '@/lib/contract';
import { getPendingOffboarding, approveOffboarding, rejectOffboarding } from '@/lib/api/offboarding';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent } from '@/components/ui/card';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

const PENDING_KEY = ['offboarding-pending'] as const;

/**
 * The HIERARCHY offboarding approval inbox (§Offboarding) — the role's ONLY write surface. Lists all
 * companies' pending cases with the MINIMAL-PII fields only (name, code, company, team, reason, last working
 * day, initiator) and approve/reject actions. Read-only styling elsewhere; here the reviewer decides.
 */
export function OffboardingInbox() {
  const { data, isLoading } = useApiQuery(PENDING_KEY, getPendingOffboarding);

  if (isLoading) return <LoadingSkeleton lines={6} />;
  if (!data || data.length === 0) {
    return (
      <EmptyState
        icon={CheckCircle2}
        title="Nothing awaiting approval"
        description="Offboarding requests from HR across all companies will appear here."
      />
    );
  }

  return (
    <div className="space-y-3">
      {data.map((row) => (
        <PendingCard key={row.caseId} row={row} />
      ))}
    </div>
  );
}

function PendingCard({ row }: { row: HierarchyPendingRow }) {
  return (
    <Card>
      <CardContent className="flex flex-col gap-4 p-5 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-semibold">{row.employeeName ?? '—'}</span>
            {row.employeeCode ? (
              <span className="font-mono text-xs text-muted-foreground">{row.employeeCode}</span>
            ) : null}
          </div>
          <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
            <span className="flex items-center gap-1.5">
              <Building2 className="size-3.5" />
              {row.companyName ?? '—'}
              {row.teamName ? ` · ${row.teamName}` : ''}
            </span>
            <span className="flex items-center gap-1.5">
              <CalendarOff className="size-3.5" />
              Last day {row.lastWorkingDay}
            </span>
          </div>
          <p className="text-sm">
            <span className="text-muted-foreground">Reason: </span>
            {row.reason}
          </p>
          <p className="text-xs text-muted-foreground">
            Requested by {row.initiatedByName ?? '—'}
            {row.initiatedAt ? ` · ${new Date(row.initiatedAt).toLocaleDateString()}` : ''}
          </p>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <DecisionDialog row={row} mode="approve" />
          <DecisionDialog row={row} mode="reject" />
        </div>
      </CardContent>
    </Card>
  );
}

function DecisionDialog({ row, mode }: { row: HierarchyPendingRow; mode: 'approve' | 'reject' }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [note, setNote] = React.useState('');
  const isReject = mode === 'reject';

  const decide = useApiMutation(
    () =>
      isReject
        ? rejectOffboarding(row.caseId, note.trim())
        : approveOffboarding(row.caseId, note.trim() || undefined),
    {
      successMessage: isReject ? 'Offboarding rejected' : 'Offboarding approved',
      onSuccess: () => {
        setOpen(false);
        void queryClient.invalidateQueries({ queryKey: PENDING_KEY });
      },
    },
  );

  const valid = !isReject || note.trim().length > 0;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant={isReject ? 'outline' : 'success'} size="sm">
          {isReject ? <XCircle className="size-4" /> : <CheckCircle2 className="size-4" />}
          {isReject ? 'Reject' : 'Approve'}
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{isReject ? 'Reject offboarding' : 'Approve offboarding'}</DialogTitle>
          <DialogDescription>
            {row.employeeName ?? 'This employee'} · {row.companyName ?? ''}
            {isReject
              ? ' — this is terminal; HR may submit a fresh request later.'
              : ' — HR is notified of the decision.'}
          </DialogDescription>
        </DialogHeader>
        <div className={cn(surface('subtle'), 'space-y-1 p-3 text-sm')}>
          <p>
            <span className="text-muted-foreground">Reason: </span>
            {row.reason}
          </p>
          <p className="flex items-center gap-1.5 text-muted-foreground">
            <CalendarOff className="size-3.5" /> Last working day {row.lastWorkingDay}
          </p>
        </div>
        <div className="space-y-1.5">
          <label className="text-sm font-medium" htmlFor={`note-${row.caseId}-${mode}`}>
            {isReject ? 'Reason for rejection (required)' : 'Note (optional)'}
          </label>
          <Input
            id={`note-${row.caseId}-${mode}`}
            value={note}
            onChange={(e) => setNote(e.target.value)}
          />
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={decide.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            variant={isReject ? 'destructive' : 'success'}
            onClick={() => decide.mutate()}
            disabled={!valid || decide.isPending}
          >
            {decide.isPending
              ? isReject
                ? 'Rejecting…'
                : 'Approving…'
              : isReject
                ? 'Reject'
                : 'Approve'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
