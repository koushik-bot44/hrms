'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import dynamic from 'next/dynamic';
import { useRouter } from 'next/navigation';
import { ArrowLeft, ShieldCheck } from 'lucide-react';
import {
  SendBackSchema,
  type EmployeeRecord,
  type RevealedSensitive,
  type ReviewDecision,
  type SendBackInput,
} from '@/lib/contract';
import { getEmployeeRecord, reviewDocument, reviewForm, revealSensitive } from '@/lib/api/review';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { useCompanyPath } from '@/lib/auth/use-company-path';
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
import { EditEmployeeInfoDialog } from '@/components/employee-info/edit-employee-info-dialog';

type ReviewVars = { kind: ItemKind; id: string; decision: ReviewDecision; reason?: string };

const recordKey = (id: string) => ['hr-record', id] as const;

// HR entry for an existing employee pulls in the whole onboarding stepper — loaded only when such a record opens.
const ExistingEmployeeEntry = dynamic(
  () => import('@/components/hr/existing-employee-entry').then((m) => m.ExistingEmployeeEntry),
  { loading: () => <RecordSkeleton /> },
);

/**
 * Apply a decision locally so the badges + approve/reject gate + derived employee status react
 * optimistically, mirroring the server recompute: REVISION_REQUESTED while any item is sent back, else
 * HR_VERIFIED once every item is verified (opens the approve/reject decision), else SUBMITTED.
 */
function patchRecord(rec: EmployeeRecord, v: ReviewVars): EmployeeRecord {
  // Empty string clears it (the field is non-null in the record type; '' reads as "no note").
  const note = v.decision === 'REVISION_REQUESTED' ? v.reason ?? '' : '';
  // Form 2 is HR/SA-authored (§3.2) — it is never verified/sent-back, so it plays no part in the gate.
  let { form1, form3, documents } = rec;
  if (v.kind === 'form') {
    if (v.id === 'FORM1' && form1) form1 = { ...form1, status: v.decision, revisionNote: note };
    if (v.id === 'FORM3') form3 = form3.map((e) => ({ ...e, status: v.decision, revisionNote: note }));
  } else {
    documents = documents.map((d) => (d.id === v.id ? { ...d, status: v.decision, revisionNote: note } : d));
  }
  const anyRevision =
    form1?.status === 'REVISION_REQUESTED' ||
    form3.some((e) => e.status === 'REVISION_REQUESTED') ||
    documents.some((d) => d.status === 'REVISION_REQUESTED');
  const allVerified =
    !!form1 &&
    form1.status === 'VERIFIED' &&
    form3.every((e) => e.status === 'VERIFIED') &&
    documents.length > 0 &&
    documents.every((d) => d.status === 'VERIFIED');
  const underReview =
    rec.status === 'SUBMITTED' || rec.status === 'REVISION_REQUESTED' || rec.status === 'HR_VERIFIED';
  const status = underReview
    ? anyRevision
      ? 'REVISION_REQUESTED'
      : allVerified
        ? 'HR_VERIFIED'
        : 'SUBMITTED'
    : rec.status;
  return { ...rec, status, form1, form3, documents, reviewComplete: allVerified };
}

/** Verify / send items back by INTERNAL id, then approve or reject once all are verified (§3.3). */
export function VerificationWorkspace({ employeeId }: { employeeId: string }) {
  const queryClient = useQueryClient();
  const router = useRouter();
  const cp = useCompanyPath();
  const [sendingBack, setSendingBack] = React.useState<{ kind: ItemKind; id: string; label: string } | null>(null);
  const [revealed, setRevealed] = React.useState<RevealedSensitive | null>(null);

  const recordQuery = useApiQuery(
    recordKey(employeeId),
    (signal) => getEmployeeRecord(employeeId, signal),
    { retry: false },
  );

  const reviewMutation = useApiMutation(
    (v: ReviewVars) =>
      v.kind === 'form'
        ? reviewForm(employeeId, v.id, { decision: v.decision, reason: v.reason })
        : reviewDocument(employeeId, v.id, { decision: v.decision, reason: v.reason }),
    {
      successMessage: (_r, v) => {
        const item = v.kind === 'form' ? 'Form' : 'Document';
        if (v.decision === 'VERIFIED') return `${item} verified`;
        if (v.decision === 'REVISION_REQUESTED') return `${item} sent back for revision`;
        return `${item} rejected`;
      },
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

  const revealMutation = useApiMutation(() => revealSensitive(employeeId), {
    successMessage: 'Sensitive fields revealed (audited)',
    onSuccess: (data) => setRevealed(data),
  });

  const record = recordQuery.data;
  // HR can act while the employee is under review — verifying items (SUBMITTED / a revision in flight) AND
  // from the verified state (HR_VERIFIED), where the Approve / Reject decision lives (and items can still
  // be sent back). Locked once APPROVED / REJECTED.
  const editable =
    record?.status === 'SUBMITTED' ||
    record?.status === 'REVISION_REQUESTED' ||
    record?.status === 'HR_VERIFIED';

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

  // An EXISTING employee's record is entered by HR until approved (§3.2) — no invite, no verify step.
  if (
    record.onboardingType === 'EXISTING_EMPLOYEE' &&
    (record.status === 'INVITED' || record.status === 'IN_PROGRESS')
  ) {
    return (
      <div className="space-y-6">
        <Button variant="ghost" size="sm" onClick={() => router.push(cp('/hr/employees'))}>
          <ArrowLeft className="size-4" />
          Back to queue
        </Button>
        <ExistingEmployeeEntry
          record={record}
          onChanged={() => {
            void queryClient.invalidateQueries({ queryKey: recordKey(employeeId) });
            void queryClient.invalidateQueries({ queryKey: ['hr-employees'] });
            void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
          }}
        />
      </div>
    );
  }

  function verify(kind: ItemKind, id: string) {
    reviewMutation.mutate({ kind, id, decision: 'VERIFIED' });
  }
  // Send-back just opens the note dialog (Preview is a separate action on documents).
  function startSendBack(kind: ItemKind, id: string, label: string) {
    setSendingBack({ kind, id, label });
  }
  function confirmSendBack(note: string) {
    if (!sendingBack) return;
    reviewMutation.mutate({ kind: sendingBack.kind, id: sendingBack.id, decision: 'REVISION_REQUESTED', reason: note });
    setSendingBack(null);
  }

  return (
    <div className="space-y-6">
      <Button variant="ghost" size="sm" onClick={() => router.push(cp('/hr/employees'))}>
        <ArrowLeft className="size-4" />
        Back to queue
      </Button>
      <RecordView
        record={record}
        editable={Boolean(editable)}
        busy={reviewMutation.isPending}
        revealed={revealed}
        enableSendAgreements
        enableOffboarding
        onReveal={() => revealMutation.mutate()}
        onVerify={verify}
        onSendBack={startSendBack}
        onDecided={() => {
          void queryClient.invalidateQueries({ queryKey: ['dashboard'] });
          router.push(cp('/hr/employees'));
        }}
        form2EditAction={
          record.status === 'INVITED' ? (
            <EditEmployeeInfoDialog
              employeeId={record.id}
              form2={record.form2}
              employeeCode={record.employeeCode}
              onboardingType={record.onboardingType}
              onSaved={() => queryClient.invalidateQueries({ queryKey: recordKey(employeeId) })}
            />
          ) : null
        }
      />
      <SendBackDialog
        open={Boolean(sendingBack)}
        label={sendingBack?.label ?? ''}
        onCancel={() => setSendingBack(null)}
        onConfirm={confirmSendBack}
      />
    </div>
  );
}

function SendBackDialog({
  open,
  label,
  onCancel,
  onConfirm,
}: {
  open: boolean;
  label: string;
  onCancel: () => void;
  onConfirm: (note: string) => void;
}) {
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SendBackInput>({
    resolver: zodResolver(SendBackSchema),
    defaultValues: { note: '' },
  });

  React.useEffect(() => {
    if (open) reset({ note: '' });
  }, [open, reset]);

  const submit = handleSubmit((v) => onConfirm(v.note.trim()));

  return (
    <Dialog open={open} onOpenChange={(next) => (next ? null : onCancel())}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Send “{label}” back for revision</DialogTitle>
          <DialogDescription>
            Tell the employee what to fix. Only this item reopens for them; everything else stays
            locked. The note is shown to the employee and recorded in the audit trail.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={submit} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="send-back-note" className="text-sm font-medium">
              Note <span className="text-destructive">*</span>
            </label>
            <textarea
              id="send-back-note"
              rows={3}
              placeholder="e.g. The PAN scan is blurry — please re-upload a clearer copy."
              className="flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              aria-invalid={Boolean(errors.note)}
              {...register('note')}
            />
            {errors.note ? <p className="text-xs text-destructive">{errors.note.message}</p> : null}
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={onCancel}>
              Cancel
            </Button>
            <Button type="submit">Send back for revision</Button>
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
