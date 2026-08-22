import { Suspense } from 'react';
import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { LoadingSkeleton } from '@/components/loading-skeleton';
import { OffboardingTab } from '@/components/hierarchy/offboarding-tab';

export const metadata: Metadata = { title: 'Offboarding' };

/**
 * The HIERARCHY Offboarding tab (§3.6) — the single home for offboarding: the actionable approval queue (the
 * role's only write surface), summary tiles, and the full, filterable case history, all over the minimal-PII
 * contract. The old pending-only inbox lived here too; it is now the top section of this one page.
 */
export default function HierarchyOffboardingPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Offboarding"
        description="Approve or reject offboarding requests, and review every case across all companies — filter by company, date and status."
        editorial
      />
      <Suspense fallback={<LoadingSkeleton lines={8} />}>
        <OffboardingTab />
      </Suspense>
    </div>
  );
}
