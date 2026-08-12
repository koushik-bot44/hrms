'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { FileText, ExternalLink, FileSignature, Upload, Eye } from 'lucide-react';
import type { IssueLetterRequest, LetterGender, LetterIssueSpec } from '@/lib/contract';
import {
  getRecordLetters,
  previewLetter,
  issueLetter,
  uploadLetterFile,
  resolveLetter,
} from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { surface } from '@/components/ui/surface';
import { cn } from '@/lib/utils';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog';

/**
 * The HR record's offboarding Letters section (§3.6 stage 3). The Relieving + Experience letters are
 * COMPANY-ISSUED: HR fills the per-case details (dates, designation, tenure; a he/she selector on the
 * Experience letter), previews the substituted text, and issues — the PDF is generated from a single-source
 * template and sent to the employee, who never fills or signs it. Issuing resolves an open employee request;
 * uploading a prepared PDF remains as a fallback when the employee has an open request. Enabled once every
 * offboarding document is verified; re-issuable.
 */
export function OffboardingLetters({ employeeId }: { employeeId: string }) {
  const key = ['offboarding-letters', employeeId] as const;
  const { data } = useApiQuery(key, (s) => getRecordLetters(employeeId, s));

  if (!data) return null;

  return (
    <div className="space-y-2 border-t pt-4">
      <h4 className="text-sm font-semibold text-muted-foreground">Letters</h4>
      {!data.gateOpen ? (
        <p className="text-sm text-muted-foreground">
          The relieving and experience letters can be issued once every offboarding document is verified.
        </p>
      ) : null}
      <div className="space-y-2">
        {data.letters.map((l) => (
          <LetterRow key={l.type} spec={l} employeeId={employeeId} gateOpen={data.gateOpen} letterKey={key} />
        ))}
      </div>
    </div>
  );
}

function LetterRow({
  spec,
  employeeId,
  gateOpen,
  letterKey,
}: {
  spec: LetterIssueSpec;
  employeeId: string;
  gateOpen: boolean;
  letterKey: readonly unknown[];
}) {
  const requested = spec.requestStatus === 'SUBMITTED' || spec.requestStatus === 'IN_PROGRESS';

  return (
    <div className={cn(surface('subtle'), 'flex flex-wrap items-center gap-3 p-3')}>
      <FileText className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      <span className="min-w-0 flex-1 truncate text-sm font-medium">{spec.title}</span>
      {spec.issued ? (
        <Badge variant="success">Issued</Badge>
      ) : requested ? (
        <Badge variant="warning">Requested</Badge>
      ) : null}
      {spec.downloadUrl ? (
        <a href={spec.downloadUrl} target="_blank" rel="noreferrer">
          <Button type="button" variant="outline" size="sm">
            <ExternalLink />
            Open
          </Button>
        </a>
      ) : null}
      <IssueLetterDialog spec={spec} employeeId={employeeId} gateOpen={gateOpen} letterKey={letterKey} />
      {requested ? <UploadFallback spec={spec} employeeId={employeeId} letterKey={letterKey} /> : null}
    </div>
  );
}

function IssueLetterDialog({
  spec,
  employeeId,
  gateOpen,
  letterKey,
}: {
  spec: LetterIssueSpec;
  employeeId: string;
  gateOpen: boolean;
  letterKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const [open, setOpen] = React.useState(false);
  const [values, setValues] = React.useState<Record<string, string>>({});
  const [gender, setGender] = React.useState<LetterGender | null>(null);
  const [previewHtml, setPreviewHtml] = React.useState<string | null>(null);

  React.useEffect(() => {
    if (open) {
      setValues(Object.fromEntries(spec.fields.map((f) => [f.key, f.value ?? ''])));
      setGender(spec.gender ?? (spec.requiresGender ? 'MALE' : null));
      setPreviewHtml(null);
    }
  }, [open, spec]);

  const body = (): IssueLetterRequest => ({
    hrValues: values,
    gender: spec.requiresGender ? gender : null,
  });

  const preview = useApiMutation(() => previewLetter(employeeId, spec.type, body()), {
    onSuccess: (r) => setPreviewHtml(r.bodyHtml),
  });
  const issue = useApiMutation(() => issueLetter(employeeId, spec.type, body()), {
    successMessage: 'Letter issued and sent to the employee',
    onSuccess: () => {
      setOpen(false);
      void queryClient.invalidateQueries({ queryKey: letterKey });
    },
  });

  const missing =
    spec.fields.some((f) => f.required && !(values[f.key] ?? '').trim()) ||
    (spec.requiresGender && !gender);

  const setField = (key: string, v: string) => {
    setValues((prev) => ({ ...prev, [key]: v }));
    setPreviewHtml(null);
  };

  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button type="button" variant="outline" size="sm" disabled={!gateOpen}>
          <FileSignature className="size-4" />
          {spec.issued ? 'Re-issue' : 'Issue'}
        </Button>
      </DialogTrigger>
      <DialogContent className="max-h-[85vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>
            {spec.issued ? 'Re-issue' : 'Issue'} {spec.title}
          </DialogTitle>
          <DialogDescription>
            Fill the details, preview, then issue. The letter is generated as a PDF and sent to the employee —
            they never fill or sign it.
          </DialogDescription>
        </DialogHeader>
        <div className="grid gap-3 sm:grid-cols-2">
          {spec.fields.map((f) => (
            <div key={f.key} className="space-y-1.5">
              <label className="text-xs font-medium" htmlFor={`lf-${spec.type}-${f.key}`}>
                {f.label}
                {f.required ? ' *' : ''}
              </label>
              <Input
                id={`lf-${spec.type}-${f.key}`}
                value={values[f.key] ?? ''}
                onChange={(e) => setField(f.key, e.target.value)}
              />
            </div>
          ))}
          {spec.requiresGender ? (
            <div className="space-y-1.5">
              <span className="text-xs font-medium">Pronoun *</span>
              <div className="flex gap-2">
                {(['MALE', 'FEMALE'] as LetterGender[]).map((g) => (
                  <Button
                    key={g}
                    type="button"
                    size="sm"
                    variant={gender === g ? 'default' : 'outline'}
                    onClick={() => {
                      setGender(g);
                      setPreviewHtml(null);
                    }}
                  >
                    {g === 'MALE' ? 'He / Him' : 'She / Her'}
                  </Button>
                ))}
              </div>
            </div>
          ) : null}
        </div>
        {previewHtml !== null ? (
          <div className="space-y-1.5">
            <span className="text-xs font-medium text-muted-foreground">Preview</span>
            <div
              // On mobile the preview grows and the DIALOG scrolls (one scroller, no nested-scroll fight);
              // only on desktop is it capped to its own scroll region.
              className="rounded-md border bg-background p-4 text-sm sm:max-h-72 sm:overflow-y-auto [&_h1]:mb-2 [&_h1]:text-base [&_h1]:font-semibold [&_p]:my-2"
              // The body is our own template with server-escaped token values (no user HTML).
              dangerouslySetInnerHTML={{ __html: previewHtml }}
            />
          </div>
        ) : null}
        <div className="flex flex-wrap justify-end gap-2 pt-2">
          <Button type="button" variant="ghost" onClick={() => setOpen(false)}>
            Cancel
          </Button>
          <Button type="button" variant="outline" disabled={preview.isPending} onClick={() => preview.mutate()}>
            <Eye className="size-4" />
            {preview.isPending ? 'Rendering…' : 'Preview'}
          </Button>
          <Button type="button" disabled={missing || issue.isPending} onClick={() => issue.mutate()}>
            {issue.isPending ? 'Issuing…' : spec.issued ? 'Re-issue' : 'Issue'}
          </Button>
        </div>
      </DialogContent>
    </Dialog>
  );
}

/** Upload a prepared PDF instead of generating one — the fallback when the employee has an open request. */
function UploadFallback({
  spec,
  employeeId,
  letterKey,
}: {
  spec: LetterIssueSpec;
  employeeId: string;
  letterKey: readonly unknown[];
}) {
  const queryClient = useQueryClient();
  const inputRef = React.useRef<HTMLInputElement>(null);

  const fulfil = useApiMutation(
    async (file: File) => {
      const docId = await uploadLetterFile(employeeId, spec.type, file);
      return resolveLetter(employeeId, spec.type, [docId]);
    },
    {
      successMessage: 'Letter uploaded and sent to the employee',
      onSuccess: () => queryClient.invalidateQueries({ queryKey: letterKey }),
    },
  );

  return (
    <>
      <input
        ref={inputRef}
        type="file"
        accept="application/pdf"
        className="hidden"
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) fulfil.mutate(file);
          e.target.value = '';
        }}
      />
      <Button
        type="button"
        variant="ghost"
        size="sm"
        disabled={fulfil.isPending}
        onClick={() => inputRef.current?.click()}
      >
        <Upload className="size-4" />
        {fulfil.isPending ? 'Uploading…' : 'Upload instead'}
      </Button>
    </>
  );
}
