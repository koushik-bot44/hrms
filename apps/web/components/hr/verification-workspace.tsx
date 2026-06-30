'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { ArrowLeft, ShieldCheck } from 'lucide-react';
import {
  RejectReasonSchema,
  type EmployeeRecord,
  type RejectReasonInput,
  type ReviewDecision,
} from '@/lib/contract';
import { getEmployeeRecord, reviewDocument, reviewSection } from '@/lib/api/review';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { EmptyState } from '@/components/empty-state';
import { RecordView, type ItemKind } from '@/components/hr/record-view';

type ReviewVars = { kind: ItemKind; id: string; decision: ReviewDecision; reason?: string };

const recordKey = (id: string) => ['hr-record', id] as const;

/** Recompute the routing gate locally so the Route button reacts to optimistic updates. */
function patchRecord(rec: EmployeeRecord, v: ReviewVars): EmployeeRecord {
  const sections =
    v.kind === 'section'
      ? rec.sections.map((s) => (s.key === v.id ? { ...s, status: v.decision } : s))
      : rec.sections;
  const documents =
    v.kind === 'document'
      ? rec.documents.map((d) => (d.id === v.id ? { ...d, status: v.decision } : d))
      : rec.documents;
  const reviewComplete =
    rec.status === 'SUBMITTED' &&
    sections.length > 0 &&
    sections.every((s) => s.status === 'VERIFIED') &&
    documents.every((d) => d.status === 'VERIFIED');
  return { ...rec, sections, documents, reviewComplete };
}

/** Verify/reject an employee's record by INTERNAL id, then route to the Manager (§3.3). */
export function VerificationWorkspace({ employeeId }: { employeeId: string }) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const [rejecting, setRejecting] = React.useState<{ kind: ItemKind; id: string; label: string } | null>(
    null,
  );

  const recordQuery = useApiQuery(
    recordKey(employeeId),
    (signal) => getEmployeeRecord(employeeId, signal),
    { retry: false },
  );

  const reviewMutation = useApiMutation(
    (v: ReviewVars) =>
      v.kind === 'section'
        ? reviewSection(employeeId, v.id, { decision: v.decision, reason: v.reason })
        : reviewDocument(employeeId, v.id, { decision: v.decision, reason: v.reason }),
    {
      successMessage: (_r, v) =>
        `${v.kind === 'section' ? 'Section' : 'Document'} ${
          v.decision === 'VERIFIED' ? 'verified' : 'rejected'
        }`,
      onMutate: async (v) => {
        const key = recordKey(employeeId);
        await queryClient.cancelQueries({ queryKey: key });
        const prev = queryClient.getQueryData<EmployeeRecord>(key);
        if (prev) queryClient.setQueryData<EmployeeRecord>(key, patchRecord(prev, v));
        return { prev };
      },
      onError: (_error, _vars, context) => {
        const ctx = context as { prev?: EmployeeRecord } | undefined;
        if (ctx?.prev) queryClient.setQueryData(recordKey(employeeId), ctx.prev);
      },
      onSuccess: (rec) => queryClient.setQueryData(recordKey(employeeId), rec),
    },
  );

  const record = recordQuery.data;
  const editable = record?.status === 'SUBMITTED';

  if (recordQuery.isLoading) return <RecordSkeleton />;
  if (recordQuery.isError || !record) {
    return (
      <EmptyState
        icon={ShieldCheck}
        title="Couldn't open this employee"
        description={
          recordQuery.error?.status === 404
            ? "This employee isn't in your workspace."
            : recordQuery.error?.message ?? 'Please try again.'
        }
      />
    );
  }

  function verify(kind: ItemKind, id: string) {
    reviewMutation.mutate({ kind, id, decision: 'VERIFIED' });
  }
  function confirmReject(reason?: string) {
    if (!rejecting) return;
    reviewMutation.mutate({ kind: rejecting.kind, id: rejecting.id, decision: 'REJECTED', reason });
    setRejecting(null);
  }

  return (
    <div className="space-y-5">
      <Button variant="ghost" size="sm" onClick={() => router.push('/hr/employees')}>
        <ArrowLeft className="size-4" />
        Back to queue
      </Button>
      <RecordView
        record={record}
        editable={Boolean(editable)}
        busy={reviewMutation.isPending}
        onVerify={verify}
        onReject={(kind, id, label) => setRejecting({ kind, id, label })}
        onRouted={() => router.push('/hr/employees')}
      />
      <RejectDialog
        open={Boolean(rejecting)}
        label={rejecting?.label ?? ''}
        onCancel={() => setRejecting(null)}
        onConfirm={confirmReject}
      />
    </div>
  );
}

function RejectDialog({
  open,
  label,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  label: string;
  onCancel: () => void;
  onConfirm: (reason?: string) => void;
}) {
  const { register, handleSubmit, reset } = useForm<RejectReasonInput>({
    resolver: zodResolver(RejectReasonSchema),
    defaultValues: { reason: '' },
  });

  React.useEffect(() => {
    if (open) reset({ reason: '' });
  }, [open, reset]);

  const submit = handleSubmit((v) => onConfirm(v.reason?.trim() ? v.reason.trim() : undefined));

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? null : onCancel())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Reject “{label}”</DialogTitle>
          <DialogDescription>
            Let the employee know what to fix. The reason is recorded in the audit trail.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="reject-reason" className="text-sm font-medium">
              Reason <span className="text-muted-foreground">(optional)</span>
            </label>
            <textarea
              id="reject-reason"
              rows={3}
              placeholder="e.g. The PAN scan is blurry — please re-upload."
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              {...register('reason')}
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button type="submit" variant="destructive">
              Reject
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function RecordSkeleton() {
  return (
    <div className="space-y-4">
      <Skeleton className="h-20 w-full" />
      <Skeleton className="h-40 w-full" />
      <Skeleton className="h-24 w-full" />
    </div>
  );
}
