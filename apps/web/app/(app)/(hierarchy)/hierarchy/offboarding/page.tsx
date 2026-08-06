import type { Metadata } from 'next';
import { PageHeader } from '@/components/page-header';
import { OffboardingInbox } from '@/components/hierarchy/offboarding-inbox';

export const metadata: Metadata = { title: 'Offboarding approvals' };

/**
 * The HIERARCHY offboarding approval inbox (§Offboarding) — the role's only write surface. A minimal-PII list
 * of pending cases across all companies; approve or reject each. Guarded to HIERARCHY by the (hierarchy) layout.
 */
export default function HierarchyOffboardingPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Offboarding approvals"
        description="Review offboarding requests from HR across all companies and approve or reject each one."
        editorial
      />
      <OffboardingInbox />
    </div>
  );
}
