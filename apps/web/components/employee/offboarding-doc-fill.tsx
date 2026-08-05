'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, ExternalLink, FileText, RotateCcw, Undo2, Clock } from 'lucide-react';
import { getMyOffboardingDocument, completeOffboardingDocument } from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { OFFBOARDING_DOC_TITLES, type OffboardingDocType } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { SignatureCapture } from '@/components/signature/signature-capture';
import { AGREEMENT_BODY_CSS } from '@/components/employee/agreement-fill';

function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

const SIG_STORE_KEY = 'agreement-signature';

/**
 * Read-and-sign one offboarding document in the workspace (§3.6 stage 2). Full text scrolls; scroll-to-end
 * unlocks consent; the per-document fields are prefilled; a fresh signature is captured; submit renders + stores
 * the PDF and notifies HR. A document sent back for revision shows HR's note and can be re-signed and resubmitted.
 */
export function OffboardingDocFill({ type }: { type: OffboardingDocType }) {
  const cp = useCompanyPath();
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['my-offboarding-doc', type], (s) =>
    getMyOffboardingDocument(type, s),
  );

  const [scrolledEnd, setScrolledEnd] = React.useState(false);
  const [consent, setConsent] = React.useState(false);
  const [fields, setFields] = React.useState<Record<string, string>>({});
  const [signatureDataUrl, setSignatureDataUrl] = React.useState<string | null>(null);
  const [previousSignature, setPreviousSignature] = React.useState<string | null>(null);
  const scrollRef = React.useRef<HTMLDivElement>(null);

  React.useEffect(() => {
    if (!data) return;
    setFields(Object.fromEntries(data.employeeFields.map((f) => [f.key, f.value ?? ''])));
  }, [data]);

  React.useEffect(() => {
    try {
      setPreviousSignature(sessionStorage.getItem(SIG_STORE_KEY));
    } catch {
      /* ignore */
    }
  }, []);

  const checkScrollEnd = React.useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 24) setScrolledEnd(true);
  }, []);
  const fillable = data?.status === 'PENDING' || data?.status === 'REVISION_REQUESTED';
  React.useEffect(() => {
    if (fillable) {
      const id = window.setTimeout(checkScrollEnd, 60);
      return () => window.clearTimeout(id);
    }
  }, [fillable, checkScrollEnd]);

  const adoptSignature = React.useCallback(async (blob: Blob) => {
    const url = await blobToDataUrl(blob);
    setSignatureDataUrl(url);
    try {
      sessionStorage.setItem(SIG_STORE_KEY, url);
      setPreviousSignature(url);
    } catch {
      /* ignore */
    }
  }, []);

  const complete = useApiMutation(
    () =>
      completeOffboardingDocument(type, {
        consentAccepted: true,
        fillValues: fields,
        signatureDataUrl: signatureDataUrl ?? '',
      }),
    {
      successMessage: 'Document signed',
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: ['my-offboarding-doc', type] });
        void queryClient.invalidateQueries({ queryKey: ['my-offboarding-docs'] });
      },
    },
  );

  if (isLoading) {
    return (
      <div className="space-y-6">
        <LoadingSkeleton lines={2} />
        <LoadingSkeleton lines={8} />
      </div>
    );
  }
  if (isError || !data) {
    return (
      <EmptyState
        icon={FileText}
        title="Couldn't load this document"
        description={error?.message ?? 'It may not have been sent to you yet.'}
      />
    );
  }

  const requiredFilled = data.employeeFields.every((f) => !f.required || (fields[f.key] ?? '').trim().length > 0);
  const done = data.status === 'SUBMITTED' || data.status === 'VERIFIED';
  const canSubmit = Boolean(fillable) && scrolledEnd && consent && Boolean(signatureDataUrl) && requiredFilled && !complete.isPending;

  return (
    <div className="space-y-6">
      <style dangerouslySetInnerHTML={{ __html: AGREEMENT_BODY_CSS }} />
      <div>
        <Link href={cp('/workspace/offboarding')}>
          <Button type="button" variant="ghost" size="sm" className="mb-2 -ml-2">
            <ArrowLeft className="size-4" />
            Back to documents
          </Button>
        </Link>
        <PageHeader
          title={data.title ?? OFFBOARDING_DOC_TITLES[type]}
          description={done ? 'You have signed and submitted this document.' : 'Read the document, complete the details, and sign.'}
          actions={<StatusBadge status={data.status} />}
        />
      </div>

      {data.status === 'REVISION_REQUESTED' && data.revisionNote ? (
        <Card className="border-warning/40 bg-warning/5">
          <CardContent className="flex items-start gap-3 py-4 text-sm">
            <Undo2 className="mt-0.5 size-4 text-warning" />
            <div>
              <div className="font-medium">HR sent this back for changes</div>
              <p className="text-muted-foreground">{data.revisionNote}</p>
            </div>
          </CardContent>
        </Card>
      ) : null}

      {done ? (
        <Card className="border-success/30 bg-success/5">
          <CardContent className="flex flex-wrap items-center gap-3 py-5">
            {data.status === 'VERIFIED' ? (
              <CheckCircle2 className="size-5 text-success" />
            ) : (
              <Clock className="size-5 text-warning" />
            )}
            <div className="min-w-0 flex-1">
              <div className="font-medium">
                {data.status === 'VERIFIED' ? 'Verified by HR' : 'Submitted — awaiting HR verification'}
              </div>
              <p className="text-sm text-muted-foreground">Your signed copy is on your record.</p>
            </div>
            {data.downloadUrl ? (
              <a href={data.downloadUrl} target="_blank" rel="noreferrer">
                <Button type="button" variant="outline" size="sm">
                  <ExternalLink />
                  View PDF
                </Button>
              </a>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">The document</CardTitle>
        </CardHeader>
        <CardContent>
          <div
            ref={scrollRef}
            onScroll={checkScrollEnd}
            className="agreement-body max-h-[60vh] overflow-y-auto rounded-md border bg-background p-5"
            dangerouslySetInnerHTML={{ __html: data.bodyHtml }}
          />
          {fillable && !scrolledEnd ? (
            <p className="mt-2 text-xs text-muted-foreground">Scroll to the end of the document to continue.</p>
          ) : null}
        </CardContent>
      </Card>

      {fillable ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Your details &amp; signature</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            {data.employeeFields.length > 0 ? (
              <div className="grid gap-4 sm:grid-cols-2">
                {data.employeeFields.map((f) => (
                  <div key={f.key} className="space-y-1.5">
                    <label className="text-sm font-medium" htmlFor={`of-${f.key}`}>
                      {f.label}
                      {f.required ? ' *' : ''}
                    </label>
                    <Input
                      id={`of-${f.key}`}
                      value={fields[f.key] ?? ''}
                      onChange={(e) => setFields((prev) => ({ ...prev, [f.key]: e.target.value }))}
                    />
                  </div>
                ))}
              </div>
            ) : null}

            <label className="flex items-start gap-3 rounded-md border p-3 text-sm has-[:disabled]:opacity-60" htmlFor="of-consent">
              <input
                id="of-consent"
                type="checkbox"
                className="mt-0.5 size-4"
                checked={consent}
                disabled={!scrolledEnd}
                onChange={(e) => setConsent(e.target.checked)}
              />
              <span>
                I have read and understood this document and agree to its terms.
                {!scrolledEnd ? (
                  <span className="block text-xs text-muted-foreground">Scroll to the end above to enable this.</span>
                ) : null}
              </span>
            </label>

            <div className="space-y-2">
              <p className="text-sm font-medium">Signature</p>
              {signatureDataUrl ? (
                <div className="flex flex-wrap items-center gap-3 rounded-md border p-3">
                  <img src={signatureDataUrl} alt="Your signature" className="h-14 w-auto max-w-[220px] bg-white" />
                  <Badge variant="success">
                    <CheckCircle2 className="size-3.5" />
                    Signed
                  </Badge>
                  <Button type="button" variant="outline" size="sm" onClick={() => setSignatureDataUrl(null)}>
                    <RotateCcw className="size-4" />
                    Redo
                  </Button>
                </div>
              ) : (
                <>
                  {previousSignature ? (
                    <div className="flex flex-wrap items-center gap-3 rounded-md border border-dashed p-3">
                      <img src={previousSignature} alt="Earlier signature" className="h-12 w-auto max-w-[200px] bg-white" />
                      <span className="text-sm text-muted-foreground">Use the signature you captured earlier?</span>
                      <Button type="button" variant="outline" size="sm" onClick={() => setSignatureDataUrl(previousSignature)}>
                        Use this signature
                      </Button>
                    </div>
                  ) : null}
                  <SignatureCapture fullName="" onAdopt={(blob) => void adoptSignature(blob)} disabled={complete.isPending} />
                </>
              )}
            </div>

            <div className="flex items-center justify-end">
              <Button type="button" onClick={() => complete.mutate()} disabled={!canSubmit}>
                {complete.isPending ? 'Submitting…' : 'Agree & submit'}
              </Button>
            </div>
          </CardContent>
        </Card>
      ) : null}
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  const tone: 'warning' | 'success' | 'neutral' | 'danger' =
    status === 'VERIFIED' ? 'success' : status === 'REVISION_REQUESTED' ? 'danger' : status === 'SUBMITTED' ? 'neutral' : 'warning';
  const label =
    status === 'VERIFIED'
      ? 'Verified'
      : status === 'REVISION_REQUESTED'
        ? 'Sent back'
        : status === 'SUBMITTED'
          ? 'Submitted'
          : 'To sign';
  return <Badge variant={tone}>{label}</Badge>;
}
