'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { Check, ClipboardCheck, UserCheck, X } from 'lucide-react';
import { RejectApprovalSchema, type Approval, type RejectApprovalInput } from '@/lib/contract';
import { approveApproval, getApprovals, rejectApproval } from '@/lib/api/manager';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';
import { ManagerRecordDialog } from '@/components/manager/record-dialog';

const KEY = ['manager-approvals'] as const;

type Deciding = { id: string; action: 'approve' | 'reject'; label: string } | null;

export function ApprovalsInbox() {
  const queryClient = useQueryClient();
  const [deciding, setDeciding] = React.useState<Deciding>(null);
  // The Manager must open an employee's record before approving them (§3.3).
  const [viewed, setViewed] = React.useState<ReadonlySet<string>>(new Set());
  const markViewed = (id: string) => setViewed((prev) => new Set(prev).add(id));
  const query = useApiQuery(KEY, getApprovals);

  const dropFromQueue = (id: string) =>
    queryClient.setQueryData<Approval[]>(KEY, (old) => old?.filter((a) => a.id !== id) ?? []);

  const optimisticRemove = async (id: string) => {
    await queryClient.cancelQueries({ queryKey: KEY });
    const prev = queryClient.getQueryData<Approval[]>(KEY);
    dropFromQueue(id);
    return { prev };
  };
  const rollback = (context: unknown) => {
    const ctx = context as { prev?: Approval[] } | undefined;
    if (ctx?.prev) queryClient.setQueryData(KEY, ctx.prev);
  };

  const approveMutation = useApiMutation((id: string) => approveApproval(id), {
    // The unique ID is minted on approval — surface it.
    successMessage: (a) =>
      a.employeeCode ? `Approved — employee ID ${a.employeeCode}` : 'Employee approved',
    onMutate: (id) => optimisticRemove(id),
    onError: (_e, _id, ctx) => rollback(ctx),
    onSuccess: () => setDeciding(null),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: KEY });
      void queryClient.invalidateQueries({ queryKey: ['manager-approvals-history'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
  });

  const rejectMutation = useApiMutation(
    (vars: { id: string; note: string }) => rejectApproval(vars.id, { note: vars.note }),
    {
      successMessage: (a) => `${a.fullName ?? 'Employee'} rejected`,
      onMutate: (vars) => optimisticRemove(vars.id),
      onError: (_e, _vars, ctx) => rollback(ctx),
      onSuccess: () => setDeciding(null),
      onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: KEY });
      void queryClient.invalidateQueries({ queryKey: ['manager-approvals-history'] });
      void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
    },
    },
  );

  if (query.isLoading) return <InboxSkeleton />;

  const approvals = query.data ?? [];
  if (approvals.length === 0) {
    return (
      <EmptyState
        icon={ClipboardCheck}
        title="No pending approvals"
        description="When HR routes a verified employee to you, it shows up here to approve."
      />
    );
  }

  return (
    <div className="space-y-4">
      {approvals.map((a) => (
        <Card key={a.id}>
          <CardHeader className="flex-row items-start justify-between gap-3 space-y-0">
            <div className="min-w-0 space-y-1">
              <CardTitle className="text-base">{a.fullName ?? a.employeeEmail}</CardTitle>
              <p className="truncate text-sm text-muted-foreground">{a.employeeEmail}</p>
              {a.designation ? (
                <p className="text-xs text-muted-foreground">{a.designation}</p>
              ) : null}
            </div>
            {a.employeeStatus ? <StatusBadge status={a.employeeStatus} /> : null}
          </CardHeader>
          <CardContent className="flex flex-wrap items-center justify-between gap-3 pt-0">
            <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
              <UserCheck className="size-3.5" aria-hidden />
              Onboarded by {a.hrName ?? 'HR'}
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <ManagerRecordDialog
                approvalId={a.id}
                label={a.fullName ?? 'Employee'}
                onOpened={() => markViewed(a.id)}
              />
              <Button
                size="sm"
                variant="outline"
                onClick={() =>
                  setDeciding({ id: a.id, action: 'reject', label: a.fullName ?? a.employeeEmail ?? '' })
                }
              >
                <X />
                Reject
              </Button>
              <Button
                size="sm"
                variant="success"
                disabled={!viewed.has(a.id)}
                title={viewed.has(a.id) ? undefined : 'View the record before approving'}
                onClick={() =>
                  setDeciding({ id: a.id, action: 'approve', label: a.fullName ?? a.employeeEmail ?? '' })
                }
              >
                <Check />
                Approve
              </Button>
            </div>
          </CardContent>
        </Card>
      ))}

      <DecisionDialog
        deciding={deciding}
        busy={approveMutation.isPending || rejectMutation.isPending}
        onCancel={() => setDeciding(null)}
        onApprove={(id) => approveMutation.mutate(id)}
        onReject={(id, note) => rejectMutation.mutate({ id, note })}
      />
    </div>
  );
}

function DecisionDialog({
  deciding,
  busy,
  onCancel,
  onApprove,
  onReject,
}: {
  deciding: Deciding;
  busy: boolean;
  onCancel: () => void;
  onApprove: (id: string) => void;
  onReject: (id: string, note: string) => void;
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<RejectApprovalInput>({
    resolver: zodResolver(RejectApprovalSchema),
    defaultValues: { note: '' },
  });

  React.useEffect(() => {
    reset({ note: '' });
  }, [deciding, reset]);

  const isReject = deciding?.action === 'reject';
  const submitReject = handleSubmit((v) => deciding && onReject(deciding.id, v.note));

  return (
    <Dialog open={Boolean(deciding)} onOpenChange={(next) => (next ? null : onCancel())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {isReject ? `Reject ${deciding?.label}` : `Approve ${deciding?.label}`}
          </DialogTitle>
          <DialogDescription>
            {isReject
              ? 'Send this back to HR with a reason. This is the final decision.'
              : 'Mark this employee approved — the final step of onboarding.'}
          </DialogDescription>
        </DialogHeader>
        {isReject ? (
          <form onSubmit={submitReject} className="space-y-4" noValidate>
            <div className="space-y-1.5">
              <label htmlFor="reject-note" className="text-sm font-medium">
                Reason
              </label>
              <textarea
                id="reject-note"
                rows={3}
                placeholder="What does the employee need to fix?"
                aria-invalid={Boolean(errors.note)}
                className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                {...register('note')}
              />
              {errors.note ? <p className="text-xs text-destructive">{errors.note.message}</p> : null}
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="ghost" onClick={onCancel}>
                Cancel
              </Button>
              <Button type="submit" variant="destructive" disabled={busy}>
                {busy ? 'Rejecting…' : 'Reject'}
              </Button>
            </div>
          </form>
        ) : (
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button
              type="button"
              variant="success"
              disabled={busy}
              onClick={() => deciding && onApprove(deciding.id)}
            >
              {busy ? 'Approving…' : 'Approve'}
            </Button>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}

function InboxSkeleton() {
  return (
    <div className="space-y-3">
      <Skeleton className="h-28 w-full" />
      <Skeleton className="h-28 w-full" />
    </div>
  );
}
