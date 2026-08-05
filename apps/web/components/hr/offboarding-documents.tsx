'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileText, CheckCircle2, Undo2, ExternalLink, Clock, Send } from 'lucide-react';
import {
  OFFBOARDING_DOC_STATUS_LABELS,
  type OffboardingDocSummary,
  type OffboardingDocType,
  type SendableDoc,
} from '@/lib/contract';
import {
  getRecordDocuments,
  sendOffboardingDocuments,
  verifyOffboardingDocument,
  sendBackOffboardingDocument,
} from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';
import { OffboardingClearanceDialog } from '@/components/hr/offboarding-clearance-dialog';
import { OffboardingLetters } from '@/components/hr/offboarding-letters';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

const STATUS_TONE: Record<string, 'warning' | 'success' | 'neutral' | 'danger'> = {
  PENDING: 'neutral',
  SUBMITTED: 'warning',
  VERIFIED: 'success',
  REVISION_REQUESTED: 'danger',
};

/**
 * The Documents section of the HR offboarding panel (§3.6 stage 2). Send the employee-facing documents with
 * per-case values, then track each through Pending → Submitted → Verified (or sent back with a note), with
 * verify / send-back actions on submissions and presigned downloads. The HR-side clearance checklist sits here too.
 */
export function OffboardingDocuments({ employeeId }: { employeeId: string }) {
  const docsKey = ['offboarding-docs', employeeId] as const;
  const { data } = useApiQuery(docsKey, (s) => getRecordDocuments(employeeId, s));
  const sendable = (data?.sendable ?? []).filter((d) => !d.alreadySent);

  return (
    <div className="space-y-3 border-t pt-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h4 className="text-sm font-semibold text-muted-foreground">Documents</h4>
        <div className="flex items-center gap-2">
          <OffboardingClearanceDialog employeeId={employeeId} />
          {sendable.length > 0 ? <SendDocsDialog employeeId={employeeId} sendable={sendable} docsKey={docsKey} /> : null}
        </div>
      </div>
      {(data?.documents ?? []).length === 0 ? (
        <p className="text-sm text-muted-foreground">No documents sent yet.</p>
      ) : (
        <div className="space-y-2">
          {data!.documents.map((d) => (
            <DocRow key={d.type} doc={d} employeeId={employeeId} docsKey={docsKey} />
          ))}
        </div>
      )}

      <OffboardingLetters employeeId={employeeId} />
    </div>
  );
}

function DocRow({
  doc,
  employeeId,
  docsKey,
}: {
  doc: OffboardingDocSummary;
  employeeId: string;
  docsKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: docsKey });
  const verify = useApiMutation(() => verifyOffboardingDocument(employeeId, doc.type), {
    successMessage: 'Document verified',
    onSuccess: invalidate,
  });

  return (
    <div className={cn(surface('subtle'), 'space-y-2 p-3')}>
      <div className="flex flex-wrap items-center gap-3">
        <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-sm font-medium">{doc.title}</span>
        <Badge variant={STATUS_TONE[doc.status] ?? 'neutral'}>
          {OFFBOARDING_DOC_STATUS_LABELS[doc.status] ?? doc.status}
        </Badge>
        {doc.downloadUrl ? (
          <a href={doc.downloadUrl} target="_blank" rel="noreferrer">
            <Button type="button" variant="outline" size="sm">
              <ExternalLink />
              Open
            </Button>
          </a>
        ) : null}
        {doc.status === 'SUBMITTED' ? (
          <>
            <Button type="button" variant="success" size="sm" disabled={verify.isPending} onClick={() => verify.mutate()}>
              <CheckCircle2 />
              Verify
            </Button>
            <SendBackDialog employeeId={employeeId} type={doc.type} docsKey={docsKey} />
          </>
        ) : null}
      </div>
      {doc.status === 'REVISION_REQUESTED' && doc.revisionNote ? (
        <p className="rounded-md border border-warning/40 bg-warning/5 px-3 py-1.5 text-xs">
          <span className="font-medium">Sent back:</span> {doc.revisionNote}
        </p>
      ) : null}
    </div>
  );
}

function SendBackDialog({
  employeeId,
  type,
  docsKey,
}: {
  employeeId: string;
  type: OffboardingDocType;
  docsKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [note, setNote] = React.useState('');
  const sendBack = useApiMutation(() => sendBackOffboardingDocument(employeeId, type, note.trim()), {
    successMessage: 'Sent back to the employee',
    onSuccess: () => {
      setOpen(false);
      setNote('');
      void queryClient.invalidateQueries({ queryKey: docsKey });
    },
  });
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <Undo2 />
          Send back
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Send back for revision</DialogTitle>
          <DialogDescription>The employee will see this note and can re-sign and resubmit.</DialogDescription>
        </DialogHeader>
        <div className="space-y-1.5">
          <label className="text-sm font-medium" htmlFor={`sb-${type}`}>
            Note (required)
          </label>
          <Input id={`sb-${type}`} value={note} onChange={(e) => setNote(e.target.value)} />
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={sendBack.isPending}>
            Cancel
          </Button>
          <Button
            type="button"
            onClick={() => sendBack.mutate()}
            disabled={note.trim().length === 0 || sendBack.isPending}
          >
            {sendBack.isPending ? 'Sending…' : 'Send back'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

function SendDocsDialog({
  employeeId,
  sendable,
  docsKey,
}: {
  employeeId: string;
  sendable: SendableDoc[];
  docsKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [checked, setChecked] = React.useState<Set<OffboardingDocType>>(new Set());
  const [values, setValues] = React.useState<Record<string, Record<string, string>>>({});

  React.useEffect(() => {
    if (open) {
      // Default all sendable checked; seed each doc's HR-field defaults.
      setChecked(new Set(sendable.map((d) => d.type)));
      const seed: Record<string, Record<string, string>> = {};
      for (const d of sendable) {
        seed[d.type] = Object.fromEntries(d.hrFields.map((f) => [f.key, f.value ?? '']));
      }
      setValues(seed);
    }
  }, [open, sendable]);

  const send = useApiMutation(
    () =>
      sendOffboardingDocuments(
        employeeId,
        sendable
          .filter((d) => checked.has(d.type))
          .map((d) => ({ type: d.type, hrValues: values[d.type] ?? {} })),
      ),
    {
      successMessage: 'Documents sent to the employee',
      onSuccess: () => {
        setOpen(false);
        void queryClient.invalidateQueries({ queryKey: docsKey });
      },
    },
  );

  const toggle = (t: OffboardingDocType) =>
    setChecked((prev) => {
      const next = new Set(prev);
      if (next.has(t)) next.delete(t);
      else next.add(t);
      return next;
    });
  const setField = (type: string, key: string, v: string) =>
    setValues((prev) => ({ ...prev, [type]: { ...(prev[type] ?? {}), [key]: v } }));

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm">
          <Send className="size-4" />
          Send documents
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Send offboarding documents</DialogTitle>
          <DialogDescription>
            Choose which to send and fill the per-case details. The employee reads and signs each in their workspace.
          </DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          {sendable.map((d) => {
            const on = checked.has(d.type);
            return (
              <div key={d.type} className={cn(surface('subtle'), 'space-y-3 p-3')}>
                <label className="flex items-center gap-3 text-sm font-medium">
                  <input type="checkbox" className="size-4" checked={on} onChange={() => toggle(d.type)} />
                  {d.title}
                </label>
                {on && d.hrFields.length > 0 ? (
                  <div className="grid gap-3 sm:grid-cols-2">
                    {d.hrFields.map((f) => (
                      <div key={f.key} className={f.kind === 'MULTILINE' ? 'space-y-1.5 sm:col-span-2' : 'space-y-1.5'}>
                        <label className="text-xs font-medium" htmlFor={`f-${d.type}-${f.key}`}>
                          {f.label}
                          {f.required ? ' *' : ''}
                        </label>
                        <Input
                          id={`f-${d.type}-${f.key}`}
                          value={values[d.type]?.[f.key] ?? ''}
                          onChange={(e) => setField(d.type, f.key, e.target.value)}
                        />
                      </div>
                    ))}
                  </div>
                ) : null}
              </div>
            );
          })}
        </div>
        <div className="flex justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)} disabled={send.isPending}>
            Cancel
          </Button>
          <Button type="button" onClick={() => send.mutate()} disabled={checked.size === 0 || send.isPending}>
            {send.isPending ? 'Sending…' : `Send ${checked.size || ''}`.trim()}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}
