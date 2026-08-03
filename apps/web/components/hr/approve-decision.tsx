'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { CheckCircle2, XCircle } from 'lucide-react';
import {
  ApproveSchema,
  type ApproveInput,
  RejectSchema,
  type RejectInput,
  type DecisionResult,
} from '@/lib/contract';
import { approveEmployee, rejectEmployee } from '@/lib/api/review';
import { useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

const TEXTAREA_CLASS =
  'flex w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

interface Props {
  employeeId: string;
  /** Enabled only when the record is fully verified (status HR_VERIFIED). */
  disabled: boolean;
  onDecided: (result: DecisionResult) => void;
}

/** HR's terminal decision on a verified record (§3.3): Approve onto a team, or terminally Reject. */
export function ApproveDecisionActions({ employeeId, disabled, onDecided }: Props) {
  return (
    <div className="flex items-center gap-2">
      <RejectDialog employeeId={employeeId} disabled={disabled} onDecided={onDecided} />
      <ApproveDialog employeeId={employeeId} disabled={disabled} onDecided={onDecided} />
    </div>
  );
}

function ApproveDialog({ employeeId, disabled, onDecided }: Props) {
  const [open, setOpen] = React.useState(false);
  const {
    register,
    handleSubmit,
    reset,
    formState: { isSubmitting },
  } = useForm<ApproveInput>({ resolver: zodResolver(ApproveSchema), defaultValues: { note: '' } });

  const mutation = useApiMutation((body: ApproveInput) => approveEmployee(employeeId, body), {
    successMessage: (r) => (r.employeeCode ? `Approved — employee ID ${r.employeeCode}` : 'Employee approved'),
    onSuccess: (r) => {
      setOpen(false);
      reset();
      onDecided(r);
    },
  });

  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) reset(); }}>
      <DialogTrigger asChild>
        <Button size="sm" disabled={disabled}>
          <CheckCircle2 />
          Approve
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Approve employee</DialogTitle>
          <DialogDescription>
            The employee joins your team; this mints their ID and notifies your team&apos;s manager. The
            record locks once approved.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit((v) => mutation.mutate(v))} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="approve-note" className="text-sm font-medium">
              Note <span className="text-muted-foreground">(optional)</span>
            </label>
            <textarea id="approve-note" rows={3} placeholder="Anything to record with the approval…" className={TEXTAREA_CLASS} {...register('note')} />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={isSubmitting}>{isSubmitting ? 'Approving…' : 'Confirm & approve'}</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function RejectDialog({ employeeId, disabled, onDecided }: Props) {
  const [open, setOpen] = React.useState(false);
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<RejectInput>({ resolver: zodResolver(RejectSchema), defaultValues: { note: '' } });

  const mutation = useApiMutation((body: RejectInput) => rejectEmployee(employeeId, body), {
    successMessage: 'Application rejected',
    onSuccess: (r) => {
      setOpen(false);
      reset();
      onDecided(r);
    },
  });

  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) reset(); }}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline" disabled={disabled}>
          <XCircle />
          Reject
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Reject application</DialogTitle>
          <DialogDescription>
            This is a terminal rejection. A note is required and is recorded in the audit trail.
          </DialogDescription>
        </DialogHeader>
        <form onSubmit={handleSubmit((v) => mutation.mutate(v))} className="space-y-4" noValidate>
          <div className="space-y-1.5">
            <label htmlFor="reject-note" className="text-sm font-medium">Reason</label>
            <textarea id="reject-note" rows={3} autoFocus placeholder="Why is this application being rejected?" className={TEXTAREA_CLASS} {...register('note')} />
            {errors.note ? <p className="text-xs text-destructive">{errors.note.message}</p> : null}
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" variant="destructive" disabled={isSubmitting}>
              {isSubmitting ? 'Rejecting…' : 'Reject application'}
            </Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
