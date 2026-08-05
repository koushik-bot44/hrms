'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { LogOut, CalendarOff, CheckCircle2, XCircle, Ban, Clock } from 'lucide-react';
import {
  OFFBOARDING_STATUS_LABELS,
  type OffboardingCaseView,
  type OffboardingStatus,
} from '@/lib/contract';
import { getOffboardingCase, initiateOffboarding, cancelOffboarding } from '@/lib/api/offboarding';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

const STATUS_TONE: Record<OffboardingStatus, 'warning' | 'success' | 'danger' | 'neutral'> = {
  PENDING_APPROVAL: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  CANCELLED: 'neutral',
};

/**
 * The HR record-view "Offboarding" panel for an APPROVED employee (§Offboarding stage 1). Initiate a case
 * (reason + last working day) for platform approval; then the case status (pending / approved / rejected with
 * note / cancelled) with a Cancel action while it is active. Stage-2 document controls will mount inside this
 * panel's body later — it is intentionally a Card with room to grow.
 */
export function OffboardingPanel({ employeeId }: { employeeId: string }) {
  const caseKey = ['offboarding-case', employeeId] as const;
  const { data, isLoading } = useApiQuery(caseKey, (signal) => getOffboardingCase(employeeId, signal));
  const current = data?.offboarding ?? null;
  const active = current?.status === 'PENDING_APPROVAL' || current?.status === 'APPROVED';

  return (
    <Card>
      <CardHeader className="flex-row items-center justify-between gap-2 space-y-0">
        <CardTitle className="flex items-center gap-2 text-base">
          <LogOut className="size-4 text-muted-foreground" aria-hidden />
          Offboarding
        </CardTitle>
        {current ? (
          <Badge variant={STATUS_TONE[current.status]}>
            {OFFBOARDING_STATUS_LABELS[current.status]}
          </Badge>
        ) : null}
      </CardHeader>
      <CardContent className="space-y-4">
        {isLoading ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : active && current ? (
          <ActiveCase employeeId={employeeId} caseView={current} caseKey={caseKey} />
        ) : (
          <NoActiveCase employeeId={employeeId} previous={current} caseKey={caseKey} />
        )}
      </CardContent>
    </Card>
  );
}

/** No active case: show any prior outcome, plus the Initiate action. */
function NoActiveCase({
  employeeId,
  previous,
  caseKey,
}: {
  employeeId: string;
  previous: OffboardingCaseView | null;
  caseKey: readonly unknown[];
}) {
  return (
    <div className="space-y-3">
      {previous?.status === 'REJECTED' ? (
        <div className="rounded-md border border-destructive/40 bg-destructive/5 p-3 text-sm">
          <p className="flex items-center gap-2 font-medium">
            <XCircle className="size-4" /> Previous request rejected
          </p>
          {previous.decisionNote ? (
            <p className="mt-1 text-muted-foreground">{previous.decisionNote}</p>
          ) : null}
        </div>
      ) : previous?.status === 'CANCELLED' ? (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <Ban className="size-4" /> A previous request was cancelled.
        </p>
      ) : (
        <p className="text-sm text-muted-foreground">
          Start the offboarding process for this employee. It goes to the platform reviewer for approval.
        </p>
      )}
      <InitiateDialog employeeId={employeeId} caseKey={caseKey} />
    </div>
  );
}

/** An active case (pending or approved): show the details + a Cancel action. */
function ActiveCase({
  employeeId,
  caseView,
  caseKey,
}: {
  employeeId: string;
  caseView: OffboardingCaseView;
  caseKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const cancel = useApiMutation((note: string) => cancelOffboarding(employeeId, note || undefined), {
    successMessage: 'Offboarding cancelled',
    onSuccess: () => queryClient.invalidateQueries({ queryKey: caseKey }),
  });
  const [cancelOpen, setCancelOpen] = React.useState(false);
  const [note, setNote] = React.useState('');

  return (
    <div className="space-y-3">
      <dl className="grid grid-cols-3 gap-2 text-sm">
        <dt className="text-muted-foreground">Reason</dt>
        <dd className="col-span-2 break-words">{caseView.reason}</dd>
        <dt className="text-muted-foreground">Last working day</dt>
        <dd className="col-span-2 flex items-center gap-1.5">
          <CalendarOff className="size-3.5 text-muted-foreground" />
          {caseView.lastWorkingDay}
        </dd>
        <dt className="text-muted-foreground">Status</dt>
        <dd className="col-span-2">
          {caseView.status === 'PENDING_APPROVAL' ? (
            <span className="flex items-center gap-1.5">
              <Clock className="size-3.5 text-warning" /> Awaiting platform approval
            </span>
          ) : (
            <span className="flex items-center gap-1.5">
              <CheckCircle2 className="size-3.5 text-success" /> Approved
              {caseView.decidedByName ? ` by ${caseView.decidedByName}` : ''}
            </span>
          )}
        </dd>
      </dl>

      {caseView.cancellable ? (
        <Dialog open={cancelOpen} onOpenChange={setCancelOpen}>
          <DialogTrigger asChild>
            <Button type="button" variant="outline" size="sm">
              <Ban className="size-4" />
              Cancel offboarding
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>Cancel this offboarding?</DialogTitle>
              <DialogDescription>
                The case is withdrawn. You can start a new one later if needed.
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-1.5">
              <label className="text-sm font-medium" htmlFor="cancel-note">
                Note (optional)
              </label>
              <Input id="cancel-note" value={note} onChange={(e) => setNote(e.target.value)} />
            </div>
            <div className="flex justify-end gap-2 pt-2">
              <Button type="button" variant="ghost" onClick={() => setCancelOpen(false)} disabled={cancel.isPending}>
                Keep it
              </Button>
              <Button
                type="button"
                variant="destructive"
                onClick={() => cancel.mutate(note, { onSuccess: () => setCancelOpen(false) })}
                disabled={cancel.isPending}
              >
                {cancel.isPending ? 'Cancelling…' : 'Cancel offboarding'}
              </Button>
            </div>
          </DialogContent>
        </Dialog>
      ) : null}
    </div>
  );
}

/** The Initiate dialog — reason + last working day. */
function InitiateDialog({ employeeId, caseKey }: { employeeId: string; caseKey: readonly unknown[] }) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [reason, setReason] = React.useState('');
  const [lastWorkingDay, setLastWorkingDay] = React.useState('');

  const initiate = useApiMutation(
    () => initiateOffboarding(employeeId, { reason: reason.trim(), lastWorkingDay }),
    {
      successMessage: 'Offboarding initiated — sent for approval',
      onSuccess: () => {
        setOpen(false);
        setReason('');
        setLastWorkingDay('');
        void queryClient.invalidateQueries({ queryKey: caseKey });
      },
    },
  );

  const valid = reason.trim().length > 0 && lastWorkingDay.length > 0;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <LogOut className="size-4" />
          Initiate offboarding
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Initiate offboarding</DialogTitle>
          <DialogDescription>
            This is sent to the platform reviewer for approval. The employee is not notified at this step.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-4">
          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="ob-reason">
              Reason
            </label>
            <Input
              id="ob-reason"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              placeholder="e.g. Resignation, role eliminated"
            />
          </div>
          <div className="space-y-1.5">
            <label className="text-sm font-medium" htmlFor="ob-lwd">
              Last working day
            </label>
            <Input
              id="ob-lwd"
              type="date"
              value={lastWorkingDay}
              onChange={(e) => setLastWorkingDay(e.target.value)}
            />
          </div>
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={initiate.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => initiate.mutate()} disabled={!valid || initiate.isPending}>
            {initiate.isPending ? 'Submitting…' : 'Initiate'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
