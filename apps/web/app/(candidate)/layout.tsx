import type { ReactNode } from 'react';

// Tier 1 — candidate route group. Public for now (no auth until Phase 5).
// PHASE 5: the candidate access guard mounts here (verify session/tier before
// rendering children). The group boundary keeps the guard a clean drop-in.
export default function CandidateLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
