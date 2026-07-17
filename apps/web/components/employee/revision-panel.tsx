'use client';

import * as React from 'react';
import { useDropzone, type FileRejection } from 'react-dropzone';
import { useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { AlertTriangle, FileText, Loader2, Send, Upload } from 'lucide-react';
import {
  DOCUMENT_TYPE_LABELS,
  MAX_UPLOAD_BYTES,
  type DocumentDto,
  type OnboardingDashboard,
} from '@/lib/contract';
import { reviseDocument, resubmitOnboarding } from '@/lib/api/onboarding';
import { useApiMutation } from '@/lib/api/hooks';
import { ApiError } from '@/lib/api/client';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Progress } from '@/components/ui/progress';
import { StatusBadge } from '@/components/status-badge';
import { Form1Step, Form3Step } from '@/components/employee/onboarding-stepper';

const FLAGGED = 'REVISION_REQUESTED';
/** Forms the employee may work on during a revision: sent back (flagged) or already revised (DRAFT). */
const OPEN_FORM_STATES = new Set([FLAGGED, 'DRAFT']);

/**
 * §3.3 — after HR sends items back, the employee fixes ONLY the flagged forms/documents (everything
 * else stays read-only), then re-submits for re-review. Shown in place of the stepper while the
 * record is REVISION_REQUESTED.
 */
export function RevisionPanel({ dashboard }: { dashboard: OnboardingDashboard }) {
  const queryClient = useQueryClient();
  const refetch = () => queryClient.invalidateQueries({ queryKey: ['onboarding'] });

  // Form 2 is HR/SA-authored (§3.2) — never sent back for revision, so it never appears here.
  const f1Status = dashboard.form1?.status;
  const f3Status = dashboard.form3[0]?.status;
  const showForm1 = f1Status != null && OPEN_FORM_STATES.has(f1Status);
  const showForm3 = f3Status != null && OPEN_FORM_STATES.has(f3Status);
  const flaggedDocs = dashboard.documents.filter((d) => d.status === FLAGGED);

  const anyStillFlagged =
    f1Status === FLAGGED || f3Status === FLAGGED || flaggedDocs.length > 0;

  const resubmit = useApiMutation(() => resubmitOnboarding(), {
    successMessage: 'Re-submitted for verification',
    onSuccess: () => refetch(),
  });

  return (
    <div className="space-y-5">
      <Card className="border-amber-500/40 bg-amber-500/5">
        <CardContent className="flex items-start gap-3 py-5">
          <AlertTriangle className="mt-0.5 size-5 shrink-0 text-amber-600" />
          <div className="space-y-1">
            <div className="font-medium">HR requested changes</div>
            <p className="text-sm text-muted-foreground">
              Update the flagged items below — each shows the note HR left. Everything else is locked.
              When you’ve fixed them all, re-submit for verification.
            </p>
          </div>
        </CardContent>
      </Card>

      {showForm1 ? (
        <RevisionSection status={f1Status} note={dashboard.form1?.revisionNote}>
          <Form1Step form1={dashboard.form1} disabled={false} onSaved={refetch} submitLabel="Save Form 1" />
        </RevisionSection>
      ) : null}

      {showForm3 ? (
        <RevisionSection status={f3Status} note={dashboard.form3[0]?.revisionNote}>
          <Form3Step form3={dashboard.form3} disabled={false} onSaved={refetch} submitLabel="Save Form 3" />
        </RevisionSection>
      ) : null}

      {flaggedDocs.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Documents to re-upload</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            {flaggedDocs.map((doc) => (
              <FlaggedDocument key={doc.id} doc={doc} onReplaced={refetch} />
            ))}
          </CardContent>
        </Card>
      ) : null}

      <div className="flex flex-col gap-2 border-t pt-4 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm text-muted-foreground">
          {anyStillFlagged
            ? 'Fix every flagged item to enable re-submission.'
            : 'All flagged items are updated — re-submit for verification.'}
        </p>
        <Button
          type="button"
          onClick={() => resubmit.mutate()}
          disabled={anyStillFlagged || resubmit.isPending}
        >
          <Send className="size-4" />
          {resubmit.isPending ? 'Re-submitting…' : 'Re-submit for verification'}
        </Button>
      </div>
    </div>
  );
}

function RevisionSection({
  status,
  note,
  children,
}: {
  status?: string;
  note?: string | null;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      {status === FLAGGED && note ? (
        <p className="rounded-md border border-amber-500/40 bg-amber-500/5 px-3 py-2 text-sm">
          <span className="font-medium">HR asked you to fix this:</span> {note}
        </p>
      ) : status === 'DRAFT' ? (
        <p className="text-xs text-muted-foreground">Updated — ready to re-submit.</p>
      ) : null}
      {children}
    </div>
  );
}

/** A flagged document: shows HR's note + a dropzone that replaces the file in place (§3.3). */
function FlaggedDocument({ doc, onReplaced }: { doc: DocumentDto; onReplaced: () => void }) {
  const [progress, setProgress] = React.useState<number | null>(null);

  const onDrop = React.useCallback(
    async (accepted: File[], rejections: FileRejection[]) => {
      if (rejections.length > 0) {
        toast.error(rejections[0]?.errors[0]?.message ?? 'File rejected');
        return;
      }
      const file = accepted[0];
      if (!file) return;
      setProgress(0);
      try {
        await reviseDocument(doc.id, file, setProgress);
        toast.success(`${file.name} uploaded`);
        onReplaced();
      } catch (error) {
        toast.error(error instanceof ApiError ? error.message : 'Upload failed');
      } finally {
        setProgress(null);
      }
    },
    [doc.id, onReplaced],
  );

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    maxFiles: 1,
    maxSize: MAX_UPLOAD_BYTES,
    accept: { 'application/pdf': ['.pdf'], 'image/png': ['.png'], 'image/jpeg': ['.jpg', '.jpeg'] },
    disabled: progress !== null,
  });

  return (
    <div className="space-y-2 rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <span className="min-w-0 flex-1 truncate text-sm font-medium">
          {DOCUMENT_TYPE_LABELS[doc.docType]}
          {doc.groupIndex ? ` · Employment ${doc.groupIndex}` : ''}
        </span>
        <StatusBadge status={doc.status} />
      </div>
      {doc.revisionNote ? (
        <p className="rounded-md border border-amber-500/40 bg-amber-500/5 px-3 py-2 text-sm">
          <span className="font-medium">HR asked you to fix this:</span> {doc.revisionNote}
        </p>
      ) : null}
      <div
        {...getRootProps()}
        className={cn(
          'flex cursor-pointer flex-col items-center justify-center gap-1.5 rounded-md border border-dashed px-4 py-5 text-center text-sm transition-colors',
          isDragActive ? 'border-primary bg-primary/5' : 'hover:border-primary/50',
          progress !== null && 'pointer-events-none opacity-70',
        )}
      >
        <input {...getInputProps()} />
        {progress !== null ? (
          <div className="w-full space-y-2">
            <div className="flex items-center justify-center gap-2 text-muted-foreground">
              <Loader2 className="size-4 animate-spin" />
              Uploading… {progress}%
            </div>
            <Progress value={progress} />
          </div>
        ) : (
          <>
            <Upload className="size-5 text-muted-foreground" />
            <span className="text-muted-foreground">
              Drag &amp; drop or <span className="font-medium text-primary">browse</span> to replace
            </span>
            <span className="text-xs text-muted-foreground">PDF, PNG or JPEG · max 10MB</span>
          </>
        )}
      </div>
    </div>
  );
}
