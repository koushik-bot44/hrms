'use client';

import { useQueryClient } from '@tanstack/react-query';
import { FileSignature } from 'lucide-react';
import { getMyOffer, acceptOffer } from '@/lib/api/onboarding';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Card, CardContent } from '@/components/ui/card';
import { LazyInlineDocument } from '@/components/documents/lazy-inline-document';
import { toOfferAccept } from '@/lib/documents/inline-markers';

/**
 * The Offer Letter screen that opens onboarding (§3.2). The invited employee reads the full company-issued
 * offer in a scrollable pane (consent unlocks only after they reach the end) and signs INSIDE the letter at
 * the signature line, then accepts — which stores the signed PDF and unlocks Forms 1–4. Company-issued: the
 * employee only reads + signs + accepts; there are no fill blanks (fields is empty) and they never edit the
 * terms. A fresh signature is captured (never carried over — reuseSignature stays off).
 */
export function OfferScreen() {
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['my-offer'], getMyOffer);

  const accept = useApiMutation(
    (payload: ReturnType<typeof toOfferAccept>) => acceptOffer(payload),
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

  return (
    <div className="space-y-6">
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

      <LazyInlineDocument
        bodyHtml={data.bodyHtml}
        fields={[]}
        fullName={data.employeeName}
        consentText="I agree to accept the employment on the terms & conditions mentioned in the above letter."
        submitLabel={accept.isPending ? 'Accepting…' : 'Accept offer'}
        submitting={accept.isPending}
        done={data.status === 'ACCEPTED'}
        downloadUrl={data.downloadUrl}
        doneTitle="Offer accepted"
        doneDescription="You can now start your onboarding forms below."
        onSubmit={(_values, signatureDataUrl) => accept.mutate(toOfferAccept(signatureDataUrl))}
      />
    </div>
  );
}
