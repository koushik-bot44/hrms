'use client';

import { CheckCircle2, ExternalLink, FileText, XCircle } from 'lucide-react';
import type { EmployeeRecord, RouteToManagerResult } from '@/lib/contract';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { StatusBadge } from '@/components/status-badge';
import { RouteToManagerDialog } from '@/components/hr/route-to-manager-dialog';

export type ItemKind = 'section' | 'document';

export function humanize(key: string): string {
  return key
    .replace(/_/g, ' ')
    .replace(/([a-z\d])([A-Z])/g, '$1 $2')
    .replace(/^./, (c) => c.toUpperCase());
}

interface RecordViewProps {
  record: EmployeeRecord;
  editable: boolean;
  busy?: boolean;
  onVerify?: (kind: ItemKind, id: string) => void;
  onReject?: (kind: ItemKind, id: string, label: string) => void;
  onRouted?: (result: RouteToManagerResult) => void;
}

/** The employee record (intake fields + sections + documents). Read-only unless editable + handlers. */
export function RecordView({ record, editable, busy = false, onVerify, onReject, onRouted }: RecordViewProps) {
  const canAct = editable && Boolean(onVerify) && Boolean(onReject);
  return (
    <div className="space-y-5">
      <Card>
        <CardHeader className="flex-col gap-3 sm:flex-row sm:items-start sm:justify-between sm:space-y-0">
          <div className="min-w-0 space-y-1">
            <CardTitle className="text-base">{record.fullName ?? record.email}</CardTitle>
            <p className="truncate text-sm text-muted-foreground">{record.email}</p>
            <p className="text-xs text-muted-foreground">
              {record.designation ?? '—'}
              {record.dateOfJoining ? ` · joins ${record.dateOfJoining}` : ''}
              {record.employeeCode ? ` · ${record.employeeCode}` : ''}
            </p>
          </div>
          <div className="flex items-center gap-3">
            <StatusBadge status={record.status} />
            {editable && onRouted ? (
              <RouteToManagerDialog
                employeeId={record.id}
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
                  {canAct ? (
                    <ItemActions
                      busy={busy}
                      onVerify={() => onVerify?.('section', s.key)}
                      onReject={() => onReject?.('section', s.key, humanize(s.key))}
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
                {canAct ? (
                  <ItemActions
                    busy={busy}
                    onVerify={() => onVerify?.('document', d.id)}
                    onReject={() => onReject?.('document', d.id, d.fileName)}
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
