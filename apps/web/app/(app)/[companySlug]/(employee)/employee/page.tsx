'use client';

import { CheckCircle2, ExternalLink, UserRound } from 'lucide-react';
import { getDashboard } from '@/lib/api/onboarding';
import { useApiQuery } from '@/lib/api/hooks';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { EmptyState } from '@/components/empty-state';
import { StatusBadge } from '@/components/status-badge';
import { TodayChip } from '@/components/accountant/today-chip';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { OnboardingStepper } from '@/components/employee/onboarding-stepper';
import { OfferScreen } from '@/components/employee/offer-screen';
import { RevisionPanel } from '@/components/employee/revision-panel';
import { GeneratedDocuments } from '@/components/employee/generated-documents';
import { OnboardingSummaryCard } from '@/components/employee/onboarding-summary-card';

const EDITABLE_STATUSES = new Set(['INVITED', 'IN_PROGRESS', 'REJECTED']);

export default function EmployeeOnboardingPage() {
  const { data, isLoading, isError, error } = useApiQuery(['onboarding'], getDashboard);

  if (isLoading) {
    return (
      <div className="space-y-6">
        <LoadingSkeleton lines={2} />
        <LoadingSkeleton lines={4} />
      </div>
    );
  }

  if (isError || !data) {
    return (
      <EmptyState
        icon={UserRound}
        title="Couldn't load your record"
        description={error?.message ?? 'Please try again.'}
      />
    );
  }

  const editable = EDITABLE_STATUSES.has(data.status);
  const revising = data.status === 'REVISION_REQUESTED';
  // The Offer Letter opens onboarding (§3.2): while it is SENT, the whole stepper is locked behind it.
  const offerPending = data.offer?.status === 'SENT';
  const acceptedOffer = data.offer?.status === 'ACCEPTED' ? data.offer : null;

  if (offerPending) {
    return (
      <div className="space-y-8">
        <PageHeader
          title="My onboarding"
          description="Read and accept your offer letter to begin — your forms unlock right after."
          actions={
            <>
              <TodayChip />
              <StatusBadge status={data.status} />
            </>
          }
          editorial
        />
        <OfferScreen />
      </div>
    );
  }

  return (
    <div className="space-y-8">
      <PageHeader
        title="My onboarding"
        description="Complete the four forms, upload your documents, sign, and submit for verification."
        actions={
          <>
            <TodayChip />
            <StatusBadge status={data.status} />
          </>
        }
        editorial
      />

      {acceptedOffer?.downloadUrl ? (
        <Card className="border-success/30 bg-success/5">
          <CardContent className="flex flex-wrap items-center gap-3 py-4">
            <CheckCircle2 className="size-5 text-success" />
            <span className="min-w-0 flex-1 text-sm font-medium">Offer letter accepted</span>
            <a href={acceptedOffer.downloadUrl} target="_blank" rel="noreferrer">
              <Button type="button" variant="outline" size="sm">
                <ExternalLink />
                View signed offer
              </Button>
            </a>
          </CardContent>
        </Card>
      ) : null}

      <OnboardingSummaryCard />

      {revising ? (
        <RevisionPanel dashboard={data} />
      ) : (
        <>
          {!editable ? (
            <Card className="border-success/30 bg-success/5">
              <CardContent className="flex items-center gap-3 py-5">
                <CheckCircle2 className="size-5 text-success" />
                <div>
                  <div className="font-medium">Submitted for verification</div>
                  <p className="text-sm text-muted-foreground">
                    Your record is locked while HR and your Manager review it. Your generated forms are below.
                  </p>
                  {/* Post-approval agreements live in the workspace (§3.5) — point the employee there. */}
                  {data.status === 'APPROVED' ? (
                    <p className="mt-1 text-sm text-muted-foreground">
                      Any company agreements to review and sign are waiting in your workspace — sign in with
                      your workspace credentials.
                    </p>
                  ) : null}
                </div>
              </CardContent>
            </Card>
          ) : data.status === 'REJECTED' ? (
            <Card className="border-destructive/30 bg-destructive/5">
              <CardContent className="py-4 text-sm">
                Your submission needs changes. Update the forms below and re-submit.
              </CardContent>
            </Card>
          ) : null}

          {editable ? <OnboardingStepper dashboard={data} disabled={false} /> : null}
        </>
      )}

      <GeneratedDocuments documents={data.generatedDocuments} />
    </div>
  );
}
