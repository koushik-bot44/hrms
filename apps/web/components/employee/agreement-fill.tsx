'use client';

import * as React from 'react';
import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, CheckCircle2, ExternalLink, FileSignature, RotateCcw } from 'lucide-react';
import { getMyAgreement, completeAgreement } from '@/lib/api/agreements';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { AGREEMENT_TITLES, isValidAadhaar, type AgreementType } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { SignatureCapture } from '@/components/signature/signature-capture';

/** The adopted signature PNG (Blob) → a data URL, matching the onboarding signature shape. */
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
 * Read-and-sign one post-approval agreement (§Agreements). The full document renders in a scrollable pane;
 * the consent checkbox unlocks only after the reader reaches the end. Per-type fields are prefilled (edited
 * values are stamped into the PDF only, never written back to the form). A FRESH signature is captured (never
 * carried over from onboarding); once signed, submitting renders + stores the PDF and notifies HR.
 */
export function AgreementFill({ type }: { type: AgreementType }) {
  const cp = useCompanyPath();
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['my-agreement', type], (s) =>
    getMyAgreement(type, s),
  );

  const [scrolledEnd, setScrolledEnd] = React.useState(false);
  const [consent, setConsent] = React.useState(false);
  const [designation, setDesignation] = React.useState('');
  const [aadhaar, setAadhaar] = React.useState('');
  const [address, setAddress] = React.useState('');
  const [mobile, setMobile] = React.useState('');
  const [signatureDataUrl, setSignatureDataUrl] = React.useState<string | null>(null);
  const [previousSignature, setPreviousSignature] = React.useState<string | null>(null);
  const scrollRef = React.useRef<HTMLDivElement>(null);

  // Prefill the editable fields once the agreement loads.
  React.useEffect(() => {
    if (!data?.prefill) return;
    setDesignation(data.prefill.designation ?? '');
    setAddress(data.prefill.address ?? '');
    setMobile(data.prefill.mobile ?? '');
  }, [data?.prefill]);

  // Offer to reuse the signature captured on a previous agreement this session (never the onboarding one).
  React.useEffect(() => {
    try {
      setPreviousSignature(sessionStorage.getItem(SIG_STORE_KEY));
    } catch {
      /* sessionStorage unavailable — no reuse offer */
    }
  }, []);

  // A document shorter than the pane needs no scroll — treat it as read.
  const checkScrollEnd = React.useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 24) setScrolledEnd(true);
  }, []);
  React.useEffect(() => {
    if (data?.status === 'PENDING') {
      // Defer to after the body has painted.
      const id = window.setTimeout(checkScrollEnd, 60);
      return () => window.clearTimeout(id);
    }
  }, [data?.status, checkScrollEnd]);

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
      completeAgreement(type, {
        consentAccepted: true,
        designation: designation || undefined,
        aadhaar: type === 'AUP' ? aadhaar : undefined,
        address: type === 'NDA' ? address : undefined,
        mobile: type === 'NDA' ? mobile : undefined,
        signatureDataUrl: signatureDataUrl ?? '',
      }),
    {
      successMessage: 'Agreement signed',
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: ['my-agreement', type] });
        void queryClient.invalidateQueries({ queryKey: ['my-agreements'] });
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
        icon={FileSignature}
        title="Couldn't load this agreement"
        description={error?.message ?? 'It may not have been sent to you yet.'}
      />
    );
  }

  const done = data.status === 'COMPLETED';
  const aadhaarOk = type !== 'AUP' || isValidAadhaar(aadhaar);
  const canSubmit =
    !done && scrolledEnd && consent && Boolean(signatureDataUrl) && aadhaarOk && !complete.isPending;

  return (
    <div className="space-y-6">
      <style dangerouslySetInnerHTML={{ __html: AGREEMENT_BODY_CSS }} />
      <div>
        <Link href={cp('/employee')}>
          <Button type="button" variant="ghost" size="sm" className="mb-2 -ml-2">
            <ArrowLeft className="size-4" />
            Back
          </Button>
        </Link>
        <PageHeader
          title={data.title ?? AGREEMENT_TITLES[type]}
          description={
            done
              ? 'You have signed this agreement.'
              : 'Read the full agreement, complete the details, and sign to submit.'
          }
          actions={<Badge variant={done ? 'success' : 'warning'}>{done ? 'Completed' : 'To sign'}</Badge>}
        />
      </div>

      {done ? (
        <Card className="border-success/30 bg-success/5">
          <CardContent className="flex flex-wrap items-center gap-3 py-5">
            <CheckCircle2 className="size-5 text-success" />
            <div className="min-w-0 flex-1">
              <div className="font-medium">Signed and submitted</div>
              <p className="text-sm text-muted-foreground">
                {data.completedAt ? `On ${new Date(data.completedAt).toLocaleString()}.` : ''} Your signed
                copy is stored on your record.
              </p>
            </div>
            {data.downloadUrl ? (
              <a href={data.downloadUrl} target="_blank" rel="noreferrer">
                <Button type="button" variant="outline" size="sm">
                  <ExternalLink />
                  View signed PDF
                </Button>
              </a>
            ) : null}
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle className="text-base">The agreement</CardTitle>
        </CardHeader>
        <CardContent>
          <div
            ref={scrollRef}
            onScroll={checkScrollEnd}
            className="agreement-body max-h-[60vh] overflow-y-auto rounded-md border bg-background p-5"
            dangerouslySetInnerHTML={{ __html: data.bodyHtml }}
          />
          {!done && !scrolledEnd ? (
            <p className="mt-2 text-xs text-muted-foreground">
              Scroll to the end of the agreement to continue.
            </p>
          ) : null}
        </CardContent>
      </Card>

      {!done ? (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">Your details &amp; signature</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            {/* Per-type fill fields (prefilled where known; edits are stamped into the PDF only). */}
            {type === 'AUP' ? (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Name" value={data.prefill.fullName} locked />
                <Field label="Employee ID" value={data.prefill.employeeCode} locked mono />
                <div className="space-y-1.5">
                  <label className="text-sm font-medium" htmlFor="ag-designation">
                    Designation
                  </label>
                  <Input
                    id="ag-designation"
                    value={designation}
                    onChange={(e) => setDesignation(e.target.value)}
                  />
                </div>
                <div className="space-y-1.5">
                  <label className="text-sm font-medium" htmlFor="ag-aadhaar">
                    Aadhaar No.
                  </label>
                  <Input
                    id="ag-aadhaar"
                    inputMode="numeric"
                    autoComplete="off"
                    placeholder="12-digit number"
                    value={aadhaar}
                    onChange={(e) => setAadhaar(e.target.value)}
                    aria-invalid={aadhaar.length > 0 && !aadhaarOk}
                  />
                  {aadhaar.length > 0 && !aadhaarOk ? (
                    <p className="text-xs text-destructive">Enter exactly 12 digits.</p>
                  ) : (
                    <p className="text-xs text-muted-foreground">
                      Stored securely and shown masked to HR.
                    </p>
                  )}
                </div>
              </div>
            ) : null}

            {type === 'NDA' ? (
              <div className="grid gap-4 sm:grid-cols-2">
                <Field label="Name" value={data.prefill.fullName} locked />
                <div className="space-y-1.5">
                  <label className="text-sm font-medium" htmlFor="ag-designation">
                    Designation
                  </label>
                  <Input
                    id="ag-designation"
                    value={designation}
                    onChange={(e) => setDesignation(e.target.value)}
                  />
                </div>
                <div className="space-y-1.5 sm:col-span-2">
                  <label className="text-sm font-medium" htmlFor="ag-address">
                    Address
                  </label>
                  <Input id="ag-address" value={address} onChange={(e) => setAddress(e.target.value)} />
                </div>
                <div className="space-y-1.5">
                  <label className="text-sm font-medium" htmlFor="ag-mobile">
                    Mobile
                  </label>
                  <Input id="ag-mobile" value={mobile} onChange={(e) => setMobile(e.target.value)} />
                </div>
              </div>
            ) : null}

            {type === 'NOTICE_PERIOD' ? (
              <Field label="Name" value={data.prefill.fullName} locked />
            ) : null}

            {/* Consent — unlocked only after the full document has been read. */}
            <label
              className="flex items-start gap-3 rounded-md border p-3 text-sm has-[:disabled]:opacity-60"
              htmlFor="ag-consent"
            >
              <input
                id="ag-consent"
                type="checkbox"
                className="mt-0.5 size-4"
                checked={consent}
                disabled={!scrolledEnd}
                onChange={(e) => setConsent(e.target.checked)}
              />
              <span>
                I have read and understood this agreement and agree to its terms.
                {!scrolledEnd ? (
                  <span className="block text-xs text-muted-foreground">
                    Scroll to the end of the agreement above to enable this.
                  </span>
                ) : null}
              </span>
            </label>

            {/* Signature — a FRESH capture (never the onboarding signature). */}
            <div className="space-y-2">
              <p className="text-sm font-medium">Signature</p>
              {signatureDataUrl ? (
                <div className="flex flex-wrap items-center gap-3 rounded-md border p-3">
                  <img
                    src={signatureDataUrl}
                    alt="Your signature"
                    className="h-14 w-auto max-w-[220px] bg-white"
                  />
                  <Badge variant="success">
                    <CheckCircle2 className="size-3.5" />
                    Signed
                  </Badge>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setSignatureDataUrl(null)}
                  >
                    <RotateCcw className="size-4" />
                    Redo
                  </Button>
                </div>
              ) : (
                <>
                  {previousSignature ? (
                    <div className="flex flex-wrap items-center gap-3 rounded-md border border-dashed p-3">
                      <img
                        src={previousSignature}
                        alt="Signature you captured earlier"
                        className="h-12 w-auto max-w-[200px] bg-white"
                      />
                      <span className="text-sm text-muted-foreground">
                        Use the signature you captured earlier?
                      </span>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => setSignatureDataUrl(previousSignature)}
                      >
                        Use this signature
                      </Button>
                    </div>
                  ) : null}
                  <SignatureCapture
                    fullName={data.prefill.fullName}
                    onAdopt={(blob) => void adoptSignature(blob)}
                    disabled={complete.isPending}
                  />
                </>
              )}
            </div>

            <div className="flex items-center justify-end gap-3">
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

/** A read-only prefilled field (locked — e.g. Name, Employee ID). */
function Field({
  label,
  value,
  locked = false,
  mono = false,
}: {
  label: string;
  value?: string | null;
  locked?: boolean;
  mono?: boolean;
}) {
  return (
    <div className="space-y-1.5">
      <label className="text-sm font-medium">{label}</label>
      <div
        className={`flex h-9 items-center rounded-md border bg-muted/40 px-3 text-sm ${mono ? 'font-mono' : ''}`}
      >
        {value || '—'}
      </div>
      {locked ? <p className="text-xs text-muted-foreground">From your record.</p> : null}
    </div>
  );
}

/** On-screen styles for the injected agreement body (matches the PDF layout at a screen-readable scale). */
const AGREEMENT_BODY_CSS = `
.agreement-body { font-size: 13px; line-height: 1.6; color: inherit; }
.agreement-body h1.title { text-align:center; font-size:18px; font-weight:700; margin:0 0 16px; text-transform:uppercase; }
.agreement-body h2 { font-size:15px; font-weight:700; margin:18px 0 6px; }
.agreement-body h2.ack-head, .agreement-body h2.dd-head { text-align:center; margin-top:22px; }
.agreement-body h3 { font-size:13px; font-weight:700; margin:12px 0 4px; }
.agreement-body p { margin:7px 0; text-align:justify; }
.agreement-body p.g { margin:9px 0; }
.agreement-body ul { margin:6px 0; padding-left:22px; list-style:disc; }
.agreement-body li { margin:3px 0; }
.agreement-body .inl { font-weight:600; }
.agreement-body table.ack { width:100%; border-collapse:collapse; margin-top:12px; }
.agreement-body table.ack td { padding:7px 8px; vertical-align:middle; }
.agreement-body table.ack td.ackk { font-weight:600; width:40%; white-space:nowrap; }
.agreement-body table.ack td.ackv { border-bottom:1px solid currentColor; opacity:0.95; }
.agreement-body table.ack td.sigcell { height:44px; }
.agreement-body table.sig-block { width:100%; border-collapse:collapse; margin-top:18px; }
.agreement-body table.sig-block td { padding:5px 10px; vertical-align:bottom; width:50%; }
.agreement-body table.sig-block td.party { font-weight:700; }
.agreement-body table.sig-block td.ackk { font-weight:600; width:24%; white-space:nowrap; }
.agreement-body table.sig-block td.ackv { border-bottom:1px solid currentColor; }
.agreement-body .sigrule { border-bottom:1px solid currentColor; margin-top:2px; }
.agreement-body .siglabel { font-size:11px; opacity:0.7; margin-top:2px; }
.agreement-body .sigval { min-height:16px; }
.agreement-body .sigblank { min-height:40px; }
.agreement-body table.dosdonts { width:100%; border-collapse:collapse; margin-top:10px; }
.agreement-body table.dosdonts th, .agreement-body table.dosdonts td { border:1px solid currentColor; padding:6px 8px; font-size:12px; vertical-align:top; text-align:left; width:50%; }
.agreement-body table.dosdonts th { font-weight:700; text-align:center; }
.agreement-body p.note { margin-top:14px; font-style:italic; }
`;
