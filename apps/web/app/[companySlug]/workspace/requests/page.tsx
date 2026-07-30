'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { keepPreviousData, useQueryClient } from '@tanstack/react-query';
import { Download, FileText, Plus } from 'lucide-react';
import {
  formatFileSize,
  REQUEST_TYPE_LABELS,
  RequestType,
  SubmitRequestSchema,
  type DocumentRequestView,
  type SubmitRequestInput,
} from '@/lib/contract';
import {
  cancelRequest,
  getMyRequests,
  openRequestDocument,
  requestKeys,
  submitRequest,
} from '@/lib/api/requests';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { StatusBadge } from '@/components/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';

const SELECT_CLASS =
  'h-11 w-full rounded-md border border-input bg-background px-3 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2';

function formatDateTime(iso: string | null): string {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : d.toLocaleString('en-IN', { day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

const EMPTY: SubmitRequestInput = { requestType: RequestType.PAYSLIP, note: '' };

/**
 * The employee's HR/Accounts Requests (§8d, Accounts side): raise a document request to the team
 * Accountant, track its state, cancel while submitted, and download the resolved document(s). The HR side
 * is a future addition.
 */
export default function RequestsPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="HR/Accounts Requests"
        description="Ask your team's Accountant for a document and track it here."
      />
      <NewRequestForm />
      <MyRequestsHistory />
    </div>
  );
}

function NewRequestForm() {
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<SubmitRequestInput>({
    resolver: zodResolver(SubmitRequestSchema),
    defaultValues: EMPTY,
  });

  const mutation = useApiMutation((body: SubmitRequestInput) => submitRequest(body), {
    successMessage: 'Request submitted',
    onSuccess: () => {
      reset(EMPTY);
      void queryClient.invalidateQueries({ queryKey: ['requests', 'me'] });
    },
    // A team with no accountant yields a 409 whose message is surfaced by the default error toast.
  });

  const onSubmit = handleSubmit((values) =>
    mutation.mutate({ ...values, note: values.note?.trim() ? values.note.trim() : undefined }),
  );

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">New request</CardTitle>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="space-y-4" noValidate>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="Document" error={errors.requestType?.message}>
              <select className={SELECT_CLASS} {...register('requestType')}>
                {Object.values(RequestType).map((t) => (
                  <option key={t} value={t}>
                    {REQUEST_TYPE_LABELS[t]}
                  </option>
                ))}
              </select>
            </Field>
          </div>
          <Field label="Note (optional)" error={errors.note?.message}>
            <textarea
              rows={3}
              placeholder="Any detail for the accountant — e.g. the period (Jan–Mar 2026)"
              aria-invalid={Boolean(errors.note)}
              className="w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              {...register('note')}
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

function MyRequestsHistory() {
  const queryClient = useQueryClient();
  const [page, setPage] = React.useState(0);
  const query = useApiQuery(requestKeys.mine(page), (signal) => getMyRequests(page, 20, signal), {
    placeholderData: keepPreviousData,
  });
  const data = query.data;

  const cancelMutation = useApiMutation((id: string) => cancelRequest(id), {
    successMessage: 'Request cancelled',
    onSettled: () => void queryClient.invalidateQueries({ queryKey: ['requests', 'me'] }),
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-base">Your requests</CardTitle>
      </CardHeader>
      <CardContent>
        {query.isLoading ? (
          <div className="space-y-2">
            <Skeleton className="h-16 w-full" />
            <Skeleton className="h-16 w-full" />
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
            <FileText className="size-6" />
            <p className="text-sm">No requests yet.</p>
          </div>
        ) : (
          <ul className={cn(surface('subtle'), 'divide-y')}>
            {data.content.map((r) => (
              <RequestRow
                key={r.id}
                request={r}
                cancelling={cancelMutation.isPending}
                onCancel={() => cancelMutation.mutate(r.id)}
              />
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

function RequestRow({
  request,
  cancelling,
  onCancel,
}: {
  request: DocumentRequestView;
  cancelling: boolean;
  onCancel: () => void;
}) {
  return (
    <li className="flex flex-wrap items-start justify-between gap-3 px-3 py-3">
      <div className="min-w-0 space-y-1">
        <p className="text-sm font-medium">
          {REQUEST_TYPE_LABELS[request.requestType]}
          <span className="ml-2 text-xs font-normal text-muted-foreground">
            {formatDateTime(request.createdAt)}
          </span>
        </p>
        {request.note ? <p className="truncate text-xs text-muted-foreground">{request.note}</p> : null}
        {request.resolvedAt ? (
          <p className="text-xs text-muted-foreground">Resolved {formatDateTime(request.resolvedAt)}</p>
        ) : null}
        {request.resolveNote ? (
          <p className="text-xs">
            <span className="text-muted-foreground">Accountant note:</span> {request.resolveNote}
          </p>
        ) : null}
        {request.status === 'RESOLVED' && request.documents.length > 0 ? (
          <div className="flex flex-wrap gap-2 pt-1">
            {request.documents.map((doc) => (
              <Button
                key={doc.id}
                type="button"
                variant="outline"
                size="sm"
                onClick={() => void openRequestDocument(request.id, doc.id)}
              >
                <Download className="size-4" />
                <span className="max-w-[12rem] truncate">{doc.fileName}</span>
                <span className="text-xs text-muted-foreground">{formatFileSize(doc.sizeBytes)}</span>
              </Button>
            ))}
          </div>
        ) : null}
      </div>
      <div className="flex items-center gap-2">
        <StatusBadge status={request.status} />
        {request.status === 'SUBMITTED' ? (
          <Button variant="outline" size="sm" disabled={cancelling} onClick={onCancel}>
            Cancel
          </Button>
        ) : null}
      </div>
    </li>
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
