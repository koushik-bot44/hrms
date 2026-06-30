'use client';

import * as React from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, ExternalLink, FileText, Search, ShieldCheck, XCircle } from 'lucide-react';
import {
  EmployeeLookupSchema,
  RejectReasonSchema,
  type EmployeeLookupInput,
  type EmployeeRecord,
  type RejectReasonInput,
  type ReviewDecision,
} from '@/lib/contract';
import { getEmployeeRecord, reviewDocument, reviewSection } from '@/lib/api/review';
import { useApiMutation, useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
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
import { RouteToManagerDialog } from '@/components/hr/route-to-manager-dialog';

type ItemKind = 'section' | 'document';
type ReviewVars = {
  employeeCode: string;
  kind: ItemKind;
  id: string;
  decision: ReviewDecision;
  reason?: string;
};

const recordKey = (code: string) => ['hr-record', code] as const;

function humanize(key: string): string {
  return key
    .replace(/_/g, ' ')
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/^./, (c) => c.toUpperCase());
}

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

export function VerificationWorkspace() {
  const queryClient = useQueryClient();
  const [code, setCode] = React.useState<string | null>(null);
  const [rejecting, setRejecting] = React.useState<{ kind: ItemKind; id: string; label: string } | null>(
    null,
  );

  const lookup = useForm<EmployeeLookupInput>({
    resolver: zodResolver(EmployeeLookupSchema),
    defaultValues: { employeeCode: '' },
  });

  const recordQuery = useApiQuery(
    code ? recordKey(code) : ['hr-record', 'idle'],
    (signal) => getEmployeeRecord(code as string, signal),
    { enabled: Boolean(code), retry: false },
  );

  const reviewMutation = useApiMutation(
    (v: ReviewVars) =>
      v.kind === 'section'
        ? reviewSection(v.employeeCode, v.id, { decision: v.decision, reason: v.reason })
        : reviewDocument(v.employeeCode, v.id, { decision: v.decision, reason: v.reason }),
    {
      successMessage: (_r, v) =>
        `${v.kind === 'section' ? 'Section' : 'Document'} ${
          v.decision === 'VERIFIED' ? 'verified' : 'rejected'
        }`,
      onMutate: async (v) => {
        const key = recordKey(v.employeeCode);
        await queryClient.cancelQueries({ queryKey: key });
        const prev = queryClient.getQueryData<EmployeeRecord>(key);
        if (prev) queryClient.setQueryData<EmployeeRecord>(key, patchRecord(prev, v));
        return { prev, key };
      },
      onError: (_error, _vars, context) => {
        const ctx = context as { prev?: EmployeeRecord; key?: readonly unknown[] } | undefined;
        if (ctx?.prev && ctx.key) queryClient.setQueryData(ctx.key, ctx.prev);
      },
      onSuccess: (rec, v) => queryClient.setQueryData(recordKey(v.employeeCode), rec),
    },
  );

  const onLookup = lookup.handleSubmit((v) => setCode(v.employeeCode));
  const record = recordQuery.data;
  const editable = record?.status === 'SUBMITTED';

  function verify(kind: ItemKind, id: string) {
    if (!record) return;
    reviewMutation.mutate({ employeeCode: record.employeeCode, kind, id, decision: 'VERIFIED' });
  }
  function confirmReject(reason?: string) {
    if (!record || !rejecting) return;
    reviewMutation.mutate({
      employeeCode: record.employeeCode,
      kind: rejecting.kind,
      id: rejecting.id,
      decision: 'REJECTED',
      reason,
    });
    setRejecting(null);
  }

  return (
    <div className="space-y-6">
      <form onSubmit={onLookup} className="flex flex-col gap-2 sm:flex-row sm:items-start" noValidate>
        <div className="flex-1 space-y-1.5">
          <label htmlFor="lookup" className="sr-only">
            Employee ID
          </label>
          <Input
            id="lookup"
            placeholder="Enter an employee ID, e.g. ACME-EMP-000001"
            className="font-mono"
            autoComplete="off"
            aria-invalid={Boolean(lookup.formState.errors.employeeCode)}
            {...lookup.register('employeeCode')}
          />
          {lookup.formState.errors.employeeCode ? (
            <p className="text-xs text-destructive">{lookup.formState.errors.employeeCode.message}</p>
          ) : null}
        </div>
        <Button type="submit">
          <Search />
          Look up
        </Button>
      </form>

      {!code ? (
        <EmptyState
          icon={ShieldCheck}
          title="Look up an employee"
          description="Enter an employee ID to review their submitted sections and documents."
        />
      ) : null}

      {code && recordQuery.isLoading ? <RecordSkeleton /> : null}

      {code && recordQuery.isError ? (
        <EmptyState
          icon={ShieldCheck}
          title="No matching employee"
          description={
            recordQuery.error?.status === 404
              ? 'No employee with that ID in your workspace.'
              : recordQuery.error?.message ?? 'Could not load the record.'
          }
        />
      ) : null}

      {record ? (
        <RecordView
          record={record}
          editable={Boolean(editable)}
          busy={reviewMutation.isPending}
          onVerify={verify}
          onReject={(kind, id, label) => setRejecting({ kind, id, label })}
          onRouted={() => {
            if (code) void queryClient.invalidateQueries({ queryKey: recordKey(code) });
          }}
        />
      ) : null}

      <RejectDialog
        open={Boolean(rejecting)}
        label={rejecting?.label ?? ''}
        onCancel={() => setRejecting(null)}
        onConfirm={confirmReject}
      />
    </div>
  );
}

// --- record ---------------------------------------------------------------

interface RecordViewProps {
  record: EmployeeRecord;
  editable: boolean;
  busy: boolean;
  onVerify: (kind: ItemKind, id: string) => void;
  onReject: (kind: ItemKind, id: string, label: string) => void;
  onRouted: () => void;
}

function RecordView({ record, editable, busy, onVerify, onReject, onRouted }: RecordViewProps) {
  return (
    <div className="space-y-5">
      <Card>
        <CardHeader className="flex-col gap-3 sm:flex-row sm:items-center sm:justify-between sm:space-y-0">
          <div className="space-y-1">
            <CardTitle className="font-mono text-base">{record.employeeCode}</CardTitle>
            <p className="text-sm text-muted-foreground">{record.email}</p>
          </div>
          <div className="flex items-center gap-3">
            <StatusBadge status={record.status} />
            {editable ? (
              <RouteToManagerDialog
                employeeCode={record.employeeCode}
                disabled={!record.reviewComplete}
                onRouted={onRouted}
              />
            ) : null}
          </div>
        </CardHeader>
        {editable && !record.reviewComplete ? (
          <CardContent className="pt-0 text-sm text-muted-foreground">
            Verify every section and document to enable routing to the Manager.
          </CardContent>
        ) : null}
        {!editable ? (
          <CardContent className="pt-0 text-sm text-muted-foreground">
            This record is no longer open for review.
          </CardContent>
        ) : null}
      </Card>

      <section className="space-y-3">
        <h3 className="text-sm font-semibold text-muted-foreground">Sections</h3>
        {record.sections.length === 0 ? (
          <p className="text-sm text-muted-foreground">No sections submitted.</p>
        ) : (
          record.sections.map((s) => (
            <Card key={s.key}>
              <CardHeader className="flex-row items-center justify-between gap-2 space-y-0">
                <CardTitle className="text-base">{humanize(s.key)}</CardTitle>
                <div className="flex items-center gap-2">
                  <StatusBadge status={s.status} />
                  {editable ? (
                    <ItemActions
                      busy={busy}
                      onVerify={() => onVerify('section', s.key)}
                      onReject={() => onReject('section', s.key, humanize(s.key))}
                    />
                  ) : null}
                </div>
              </CardHeader>
              <CardContent>
                {Object.keys(s.data).length === 0 ? (
                  <p className="text-sm text-muted-foreground">No data entered.</p>
                ) : (
                  <dl className="divide-y divide-border">
                    {Object.entries(s.data).map(([k, v]) => (
                      <div key={k} className="grid grid-cols-3 gap-2 py-1.5 text-sm">
                        <dt className="text-muted-foreground">{humanize(k)}</dt>
                        <dd className="col-span-2 break-words">
                          {v === null || v === '' || typeof v === 'object' ? '—' : String(v)}
                        </dd>
                      </div>
                    ))}
                  </dl>
                )}
              </CardContent>
            </Card>
          ))
        )}
      </section>

      <section className="space-y-3">
        <h3 className="text-sm font-semibold text-muted-foreground">Documents</h3>
        {record.documents.length === 0 ? (
          <p className="text-sm text-muted-foreground">No documents uploaded.</p>
        ) : (
          <div className="space-y-2">
            {record.documents.map((d) => (
              <div
                key={d.id}
                className="flex flex-wrap items-center gap-3 rounded-md border border-border p-3"
              >
                <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{d.fileName}</p>
                  <p className="text-xs text-muted-foreground">
                    {humanize(d.docType)} · {humanize(d.sectionKey)}
                  </p>
                </div>
                <StatusBadge status={d.status} />
                <a href={d.viewUrl} target="_blank" rel="noreferrer">
                  <Button type="button" variant="outline" size="sm">
                    <ExternalLink />
                    Preview
                  </Button>
                </a>
                {editable ? (
                  <ItemActions
                    busy={busy}
                    onVerify={() => onVerify('document', d.id)}
                    onReject={() => onReject('document', d.id, d.fileName)}
                  />
                ) : null}
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function ItemActions({
  busy,
  onVerify,
  onReject,
}: {
  busy: boolean;
  onVerify: () => void;
  onReject: () => void;
}) {
  return (
    <div className="flex items-center gap-1.5">
      <Button type="button" variant="success" size="sm" disabled={busy} onClick={onVerify}>
        <CheckCircle2 />
        Verify
      </Button>
      <Button type="button" variant="outline" size="sm" disabled={busy} onClick={onReject}>
        <XCircle />
        Reject
      </Button>
    </div>
  );
}

// --- reject dialog --------------------------------------------------------

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
