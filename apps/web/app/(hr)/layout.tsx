import type { ReactNode } from 'react';

// Tier 3 — HR operator route group. Public for now (no auth until Phase 5).
// PHASE 5: the HR access guard (highest privilege) mounts here.
export default function HrLayout({ children }: { children: ReactNode }) {
  return <>{children}</>;
}
