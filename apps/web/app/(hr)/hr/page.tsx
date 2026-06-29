import type { Metadata } from 'next';
import { Users } from 'lucide-react';
import { TierShell } from '@/components/tier-shell';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'HR' };

export default function HrPage() {
  return (
    <TierShell
      tier="Tier 3 · HR Operator"
      title="HR console"
      description="Issue documents, manage requirements, and oversee onboarding across the organization."
    >
      <EmptyState
        icon={Users}
        title="The HR console arrives in a later phase"
        hint="Issue company-authored documents, define onboarding requirements, and run external reference checks from here."
      />
    </TierShell>
  );
}
