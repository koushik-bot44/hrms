import type { Metadata } from 'next';
import { ClipboardCheck } from 'lucide-react';
import { TierShell } from '@/components/tier-shell';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Candidate' };

export default function CandidatePage() {
  return (
    <TierShell
      tier="Tier 1 · Candidate"
      title="Candidate workspace"
      description="Submit and track the evidence requested during onboarding."
    >
      <EmptyState
        icon={ClipboardCheck}
        title="The candidate experience arrives in a later phase"
        hint="You'll see each onboarding requirement, upload evidence, and follow its review status here."
      />
    </TierShell>
  );
}
