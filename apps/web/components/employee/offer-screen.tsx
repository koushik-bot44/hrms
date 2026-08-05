'use client';

import * as React from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { CheckCircle2, FileSignature, RotateCcw } from 'lucide-react';
import { getMyOffer, acceptOffer } from '@/lib/api/onboarding';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { SignatureCapture } from '@/components/signature/signature-capture';
import { AGREEMENT_BODY_CSS } from '@/components/employee/agreement-fill';

/** The adopted signature PNG (Blob) → a data URL (matches the onboarding/agreement signature shape). */
function blobToDataUrl(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result as string);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

/**
 * The Offer Letter screen that opens onboarding (§3.2). The invited employee reads the full company-issued
 * offer in a scrollable pane (the consent checkbox unlocks only after they reach the end), captures a fresh
 * signature, and accepts — which stores the signed PDF and unlocks Forms 1–4. Company-issued: the employee
 * only reads + signs + accepts; they never fill or edit the terms. Mounted only while the offer is SENT.
 */
export function OfferScreen() {
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['my-offer'], getMyOffer);

  const [scrolledEnd, setScrolledEnd] = React.useState(false);
  const [consent, setConsent] = React.useState(false);
  const [signatureDataUrl, setSignatureDataUrl] = React.useState<string | null>(null);
  const scrollRef = React.useRef<HTMLDivElement>(null);

  // A document shorter than the pane needs no scroll — treat it as read.
  const checkScrollEnd = React.useCallback(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (el.scrollHeight - el.scrollTop - el.clientHeight < 24) setScrolledEnd(true);
  }, []);
  React.useEffect(() => {
    if (data?.status === 'SENT') {
      const id = window.setTimeout(checkScrollEnd, 60);
      return () => window.clearTimeout(id);
    }
  }, [data?.status, checkScrollEnd]);

  const accept = useApiMutation(
    () => acceptOffer({ consentAccepted: true, signatureDataUrl: signatureDataUrl ?? '' }),
    {
      successMessage: 'Offer accepted — you can now start onboarding',
      onSuccess: () => {
        void queryClient.invalidateQueries({ queryKey: ['onboarding'] });
        void queryClient.invalidateQueries({ queryKey: ['my-offer'] });
      },
    },
  );

  if (isLoading) {
    return (
      <div className="space-y-6">
        <LoadingSkeleton lines={2} />
        <LoadingSkeleton lines={10} />
      </div>
    );
  }
  if (isError || !data) {
    return (
      <EmptyState
        icon={FileSignature}
        title="Couldn't load your offer letter"
        description={error?.message ?? 'Please try again.'}
      />
    );
  }

  const canAccept =
    data.status === 'SENT' && scrolledEnd && consent && Boolean(signatureDataUrl) && !accept.isPending;

  return (
    <div className="space-y-6">
      <style dangerouslySetInnerHTML={{ __html: AGREEMENT_BODY_CSS }} />

      <Card className="border-primary/30 bg-primary/5">
        <CardContent className="flex flex-wrap items-center gap-3 py-4">
          <FileSignature className="size-5 text-primary" />
          <div className="min-w-0 flex-1 text-sm">
            <div className="font-medium">Your offer letter is ready</div>
            <p className="text-muted-foreground">
              Read the full letter, then sign and accept to begin your onboarding.
            </p>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Offer Letter</CardTitle>
        </CardHeader>
        <CardContent>
          <div
            ref={scrollRef}
            onScroll={checkScrollEnd}
            className="agreement-body max-h-[60vh] overflow-y-auto rounded-md border bg-background p-5"
            dangerouslySetInnerHTML={{ __html: data.bodyHtml }}
          />
          {!scrolledEnd ? (
            <p className="mt-2 text-xs text-muted-foreground">
              Scroll to the end of the letter to continue.
            </p>
          ) : null}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle className="text-base">Accept &amp; sign</CardTitle>
        </CardHeader>
        <CardContent className="space-y-5">
          {/* Consent — unlocked only after the full letter has been read; the exact acceptance sentence. */}
          <label
            className="flex items-start gap-3 rounded-md border p-3 text-sm has-[:disabled]:opacity-60"
            htmlFor="offer-consent"
          >
            <input
              id="offer-consent"
              type="checkbox"
              className="mt-0.5 size-4"
              checked={consent}
              disabled={!scrolledEnd}
              onChange={(e) => setConsent(e.target.checked)}
            />
            <span>
              I agree to accept the employment on the terms &amp; conditions mentioned in the above letter.
              {!scrolledEnd ? (
                <span className="block text-xs text-muted-foreground">
                  Scroll to the end of the letter above to enable this.
                </span>
              ) : null}
            </span>
          </label>

          {/* Signature — a fresh capture. */}
          <div className="space-y-2">
            <p className="text-sm font-medium">Signature</p>
            {signatureDataUrl ? (
              <div className="flex flex-wrap items-center gap-3 rounded-md border p-3">
                <img
                  src={signatureDataUrl}
                  alt="Your signature"
                  className="h-14 w-auto max-w-[220px] bg-white"
                />
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
              <SignatureCapture
                fullName={data.employeeName}
                onAdopt={(blob) => void blobToDataUrl(blob).then(setSignatureDataUrl)}
                disabled={accept.isPending}
              />
            )}
          </div>

          <div className="flex items-center justify-end gap-3">
            <Button type="button" onClick={() => accept.mutate()} disabled={!canAccept}>
              <CheckCircle2 className="size-4" />
              {accept.isPending ? 'Accepting…' : 'Accept offer'}
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
