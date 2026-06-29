import type { Metadata } from 'next';
import { IdCard } from 'lucide-react';
import { TierShell } from '@/components/tier-shell';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Employee' };

export default function EmployeePage() {
  return (
    <TierShell
      tier="Tier 2 · Employee"
      title="Employee workspace"
      description="Access your issued documents and any ongoing requirements."
    >
      <EmptyState
        icon={IdCard}
        title="The employee experience arrives in a later phase"
        hint="Your issued letters and certificates — each with a verifiable unique ID — will live here."
      />
    </TierShell>
  );
}
