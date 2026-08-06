'use client';

import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, FileSignature } from 'lucide-react';
import { getMyAgreement, completeAgreement } from '@/lib/api/agreements';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { AGREEMENT_TITLES, type AgreementType } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { InlineDocument } from '@/components/documents/inline-document';
import { toAgreementComplete } from '@/lib/documents/inline-markers';

/**
 * Read-and-sign one post-approval agreement (§Agreements). The full document renders in a scrollable pane;
 * the employee fills the blanks + signs INSIDE the document (the shared InlineDocument) — no separate fields
 * panel. Consent unlocks only after the reader reaches the end; a FRESH signature is captured (never carried
 * over from onboarding). Once signed, submitting renders + stores the PDF and notifies HR — the payload is
 * unchanged.
 */
export function AgreementFill({ type }: { type: AgreementType }) {
  const cp = useCompanyPath();
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['my-agreement', type], (s) =>
    getMyAgreement(type, s),
  );

  const complete = useApiMutation(
    (payload: ReturnType<typeof toAgreementComplete>) => completeAgreement(type, payload),
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

  return (
    <div className="space-y-6">
      <div>
        <Link href={cp('/workspace/agreements')}>
          <Button type="button" variant="ghost" size="sm" className="mb-2 -ml-2">
            <ArrowLeft className="size-4" />
            Back to agreements
          </Button>
        </Link>
        <PageHeader
          title={data.title ?? AGREEMENT_TITLES[type]}
          description={
            done
              ? 'You have signed this agreement.'
              : 'Read the full agreement, fill the blanks, and sign inside it.'
          }
          actions={<Badge variant={done ? 'success' : 'warning'}>{done ? 'Completed' : 'To sign'}</Badge>}
        />
      </div>

      <InlineDocument
        bodyHtml={data.bodyHtml}
        fields={data.fields}
        fullName={data.prefill.fullName}
        consentText="I have read and understood this agreement and agree to its terms."
        submitLabel="Agree & submit"
        submitting={complete.isPending}
        done={done}
        downloadUrl={data.downloadUrl}
        reuseSignature
        doneDescription={
          data.completedAt
            ? `Signed on ${new Date(data.completedAt).toLocaleString()}. Your signed copy is stored on your record.`
            : 'Your signed copy is stored on your record.'
        }
        onSubmit={(values, signatureDataUrl) =>
          complete.mutate(toAgreementComplete(type, values, signatureDataUrl))
        }
      />
    </div>
  );
}
