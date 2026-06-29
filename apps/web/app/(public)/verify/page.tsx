import type { Metadata } from 'next';
import { ShieldCheck } from 'lucide-react';
import { TierShell } from '@/components/tier-shell';
import { EmptyState } from '@/components/empty-state';

export const metadata: Metadata = { title: 'Verify' };

export default function VerifyPage() {
  return (
    <TierShell
      tier="Tier 0 · Public"
      title="Verify a document"
      description="Anyone can confirm a CDPP record by its unique ID — no account required."
    >
      <EmptyState
        icon={ShieldCheck}
        title="Public verification arrives in Phase 4"
        hint="You'll paste a document's unique ID (e.g. STELLAR-OFR-2026-000042) to confirm its authenticity and current status."
      />
    </TierShell>
  );
}
