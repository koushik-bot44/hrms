'use client';

import * as React from 'react';
import { ExternalLink, FileText } from 'lucide-react';
import type { EmployeeRecord } from '@/lib/contract';
import { getApprovalRecord } from '@/lib/api/manager';
import { useApiQuery } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import { StatusBadge } from '@/components/status-badge';

function humanize(key: string): string {
  return key
    .replace(/_/g, ' ')
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/^./, (c) => c.toUpperCase());
}

/** Read-only view of the sections + documents HR verified, opened from a manager approval. */
export function ManagerRecordDialog({
  approvalId,
  label,
}: {
  approvalId: string;
  label: string;
}) {
  const [open, setOpen] = React.useState(false);
  const query = useApiQuery(
    ['manager-approval-record', approvalId],
    (signal) => getApprovalRecord(approvalId, signal),
    { enabled: open, retry: false },
  );
  const record = query.data;

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <FileText className="size-4" />
          View record
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[85vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{label}</DialogTitle>
          <DialogDescription>The sections and documents HR verified for this employee.</DialogDescription>
        </DialogHeader>
        {query.isLoading ? (
          <div className="space-y-3">
            <Skeleton className="h-32 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : query.isError ? (
          <p className="text-sm text-destructive">{query.error?.message ?? 'Could not load the record.'}</p>
        ) : record ? (
          <RecordBody record={record} />
        ) : null}
      </DialogContent>
    </Dialog>
  );
}

function RecordBody({ record }: { record: EmployeeRecord }) {
  return (
    <div className="space-y-5">
      <section className="space-y-3">
        <h3 className="text-sm font-semibold text-muted-foreground">Sections</h3>
        {record.sections.length === 0 ? (
          <p className="text-sm text-muted-foreground">No sections submitted.</p>
        ) : (
          record.sections.map((s) => (
            <div key={s.key} className="rounded-md border p-3">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="font-medium">{humanize(s.key)}</span>
                <StatusBadge status={s.status} />
              </div>
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
            </div>
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
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
