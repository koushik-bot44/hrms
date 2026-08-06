'use client';

import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, FileText } from 'lucide-react';
import { getMyOffboardingDocument, completeOffboardingDocument } from '@/lib/api/offboarding-docs';
import { useApiQuery, useApiMutation } from '@/lib/api/hooks';
import { OFFBOARDING_DOC_TITLES, type OffboardingDocType } from '@/lib/contract';
import { useCompanyPath } from '@/lib/auth/use-company-path';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { Button } from '@/components/ui/button';
import { StatusBadge } from '@/components/status-badge';
import { InlineDocument } from '@/components/documents/inline-document';
import { toOffboardingComplete } from '@/lib/documents/inline-markers';

/**
 * Read-and-sign one offboarding document in the workspace (§3.6). Full text scrolls; the employee fills the
 * blanks + signs INSIDE the document (the shared InlineDocument) — no separate fields panel. A document sent
 * back for revision reopens with its prior values (they arrive in the same employeeFields manifest) + HR's
 * note. Submit renders + stores the PDF and notifies HR — the fillValues payload is unchanged.
 */
export function OffboardingDocFill({ type }: { type: OffboardingDocType }) {
  const cp = useCompanyPath();
  const queryClient = useQueryClient();
  const { data, isLoading, isError, error } = useApiQuery(['my-offboarding-doc', type], (s) =>
    getMyOffboardingDocument(type, s),
  );

  const complete = useApiMutation(
    (payload: ReturnType<typeof toOffboardingComplete>) => completeOffboardingDocument(type, payload),
    {
      successMessage: 'Document signed and submitted',
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

  const done = data.status === 'SUBMITTED' || data.status === 'VERIFIED';

  return (
    <div className="space-y-6">
      <div>
        <Link href={cp('/workspace/offboarding')}>
          <Button type="button" variant="ghost" size="sm" className="mb-2 -ml-2">
            <ArrowLeft className="size-4" />
            Back to offboarding
          </Button>
        </Link>
        <PageHeader
          title={data.title ?? OFFBOARDING_DOC_TITLES[type]}
          description={
            done
              ? 'You have signed and submitted this document.'
              : 'Read the full document, fill the blanks, and sign inside it.'
          }
          actions={<StatusBadge status={data.status} />}
        />
      </div>

      <InlineDocument
        bodyHtml={data.bodyHtml}
        fields={data.employeeFields}
        consentText="I have read and understood this document and agree to its terms."
        submitLabel="Sign & submit"
        submitting={complete.isPending}
        done={done}
        downloadUrl={data.downloadUrl}
        revisionNote={data.status === 'REVISION_REQUESTED' ? data.revisionNote : null}
        reuseSignature
        doneTitle="Signed and submitted"
        doneDescription="HR will review your signed document. Your signed copy is stored on your record."
        onSubmit={(values, signatureDataUrl) =>
          complete.mutate(toOffboardingComplete(values, signatureDataUrl))
        }
      />
    </div>
  );
}
