'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import { CalendarDays, Plus } from 'lucide-react';
import { SubmitLeaveSchema, type SubmitLeaveInput, LeaveType } from '@/lib/contract';
import { cancelLeave, getMyLeave, leaveKeys, submitLeave } from '@/lib/api/leave';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { StatusBadge } from '@/components/status-badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';

const SELECT_CLASS =
  'h-10 w-full rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';
const TYPE_LABELS: Record<string, string> = { CASUAL: 'Casual', SICK: 'Sick', UNPAID: 'Unpaid' };

function formatDate(iso: string): string {
  const d = new Date(`${iso}T00:00:00`);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}

/** The employee's own leave (§8b): request time off + see the history + cancel a pending request. */
export default function LeavePage() {
  return (
    <div className="space-y-6">
      <PageHeader title="Leave" description="Request time off and track your requests." />
      <RequestLeaveForm />
      <MyLeaveHistory />
    </div>
  );
}

function RequestLeaveForm() {
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SubmitLeaveInput>({
    resolver: zodResolver(SubmitLeaveSchema),
    defaultValues: { startDate: '', endDate: '', leaveType: LeaveType.CASUAL, reason: '' },
  });

  const mutation = useApiMutation((body: SubmitLeaveInput) => submitLeave(body), {
    successMessage: 'Leave request submitted',
    onSuccess: () => {
      reset({ startDate: '', endDate: '', leaveType: LeaveType.CASUAL, reason: '' });
      void queryClient.invalidateQueries({ queryKey: ['leave', 'me'] });
    },
  });

  const onSubmit = handleSubmit((values) => mutation.mutate(values));

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Request leave</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="grid gap-4 sm:grid-cols-3">
            <Field label="From" error={errors.startDate?.message}>
              <Input type="date" aria-invalid={Boolean(errors.startDate)} {...register('startDate')} />
            </Field>
            <Field label="To" error={errors.endDate?.message}>
              <Input type="date" aria-invalid={Boolean(errors.endDate)} {...register('endDate')} />
            </Field>
            <Field label="Type" error={errors.leaveType?.message}>
              <select className={SELECT_CLASS} {...register('leaveType')}>
                {Object.values(LeaveType).map((t) => (
                  <option key={t} value={t}>
                    {TYPE_LABELS[t]}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Field label="Reason" error={errors.reason?.message}>
            <textarea
              rows={3}
              placeholder="A short reason for your manager"
              aria-invalid={Boolean(errors.reason)}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              {...register('reason')}
            />
          </Field>
          <div className="flex justify-end">
            <Button type="submit" disabled={mutation.isPending}>
              <Plus />
              {mutation.isPending ? 'Submitting…' : 'Submit request'}
            </Button>
          </div>
        </form>
      </CardContent>
    </Card>
  );
}

function MyLeaveHistory() {
  const queryClient = useQueryClient();
  const [page, setPage] = React.useState(0);
  const query = useApiQuery(leaveKeys.mine(page), (signal) => getMyLeave(page, 20, signal), {
    placeholderData: keepPreviousData,
  });
  const data = query.data;

  const cancelMutation = useApiMutation((id: string) => cancelLeave(id), {
    successMessage: 'Request cancelled',
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['leave', 'me'] }),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Your requests</CardTitle>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-14 w-full" />
            <Skeleton className="h-14 w-full" />
          </div>
        ) : query.isError ? (
          <div className="py-8 text-center text-sm text-destructive">
            Couldn&rsquo;t load your requests.{' '}
            <button type="button" onClick={() => query.refetch()} className="underline">
              Retry
            </button>
          </div>
        ) : !data || data.content.length === 0 ? (
          <div className="flex flex-col items-center gap-2 py-10 text-center text-muted-foreground">
            <CalendarDays className="size-6" />
            <p className="text-sm">No leave requests yet.</p>
          </div>
        ) : (
          <ul className="divide-y rounded-md border">
            {data.content.map((r) => (
              <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 px-3 py-3">
                <div className="min-w-0">
                  <p className="text-sm font-medium">
                    {formatDate(r.startDate)} → {formatDate(r.endDate)}
                    <span className="ml-2 text-xs font-normal text-muted-foreground">
                      {TYPE_LABELS[r.leaveType]}
                    </span>
                  </p>
                  <p className="truncate text-xs text-muted-foreground">{r.reason}</p>
                  {r.decisionNote ? (
                    <p className="mt-0.5 text-xs">
                      <span className="text-muted-foreground">Manager note:</span> {r.decisionNote}
                    </p>
                  ) : null}
                </div>
                <div className="flex items-center gap-2">
                  <StatusBadge status={r.status} />
                  {r.status === 'PENDING' ? (
                    <Button
                      variant="outline"
                      size="sm"
                      disabled={cancelMutation.isPending}
                      onClick={() => cancelMutation.mutate(r.id)}
                    >
                      Cancel
                    </Button>
                  ) : null}
                </div>
              </li>
            ))}
          </ul>
        )}

        {data && data.totalPages > 1 ? (
          <div className="flex items-center justify-between pt-4 text-sm">
            <span className="text-xs text-muted-foreground">
              Page {data.page + 1} of {data.totalPages}
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
      </CardContent>
    </Card>
  );
}

function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-1.5">
      <label className="text-sm font-medium">{label}</label>
      {children}
      {error ? <p className="text-xs text-destructive">{error}</p> : null}
    </div>
  );
}
